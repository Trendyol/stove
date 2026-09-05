package com.trendyol.stove.dashboard

import com.trendyol.stove.dashboard.api.DashboardEvent
import com.trendyol.stove.dashboard.api.DashboardEventBatch
import com.trendyol.stove.dashboard.api.RunStartedEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class DashboardSpoolTest : FunSpec({
  test("pending records and delivery ownership recover after an abrupt JVM exit") {
    val options = DashboardSpoolOptions(Files.createTempDirectory("stove-spool-crash"))
    val originalIds = runSpoolProcess(options.directory)
    originalIds.size shouldBe 10
    DashboardSpool(DashboardIngestion.Grpc(), options).use { spool ->
      spool.peek().map { it.event.eventId } shouldBe originalIds
      spool.peek().map { it.event.sequence } shouldBe (1L..10L).toList()
      spool.tryAcquireDelivery()!!.use {
        DashboardSpool(DashboardIngestion.Grpc(), options).close()
        runSpoolProcess(options.directory, "probe") shouldBe listOf("false")
      }
    }
  }

  test("reopening retains pending identities and sequences including acknowledged prefixes") {
    val options = DashboardSpoolOptions(Files.createTempDirectory("stove-spool-recovery"))
    val endpoint = DashboardIngestion.Grpc()
    val pending = DashboardSpool(endpoint, options).use { spool ->
      spool.append(spoolEvent())
      spool.append(spoolEvent())
      val records = spool.peek()
      spool.acknowledge(records.take(1))
      records.last().event
    }
    DashboardSpool(endpoint, options).use { spool ->
      spool.peek().single().event shouldBe pending
      spool.append(spoolEvent())
      spool.peek().map { it.event.sequence } shouldBe listOf(2L, 3L)
      spool.acknowledge(spool.peek())
      spool.status().pendingEvents shouldBe 0
      spool.status().pendingBytes shouldBe 0
    }
    DashboardSpool(endpoint, options).use { spool ->
      spool.append(spoolEvent())
      spool.peek().single().event.sequence shouldBe 4
    }
  }

  test("multiple producers share ordering and only one can acquire delivery") {
    val options = DashboardSpoolOptions(Files.createTempDirectory("stove-spool-producers"))
    val spools = List(4) { DashboardSpool(DashboardIngestion.Grpc(), options) }
    try {
      spools.first().tryAcquireDelivery()!!.use {
        spools.drop(1).forEach { it.tryAcquireDelivery() shouldBe null }
        coroutineScope {
          spools.map { spool -> async(Dispatchers.IO) { repeat(25) { spool.append(spoolEvent()) } } }.awaitAll()
        }
      }
      spools.last().tryAcquireDelivery()!!.use {
        val records = spools.last().peek()
        records.map { it.event.sequence } shouldBe (1L..100L).toList()
        records.map { it.event.eventId }.toSet().size shouldBe 100
      }
    } finally {
      spools.forEach { it.close() }
    }
  }

  test("quota failure rolls back identity allocation and preserves pending evidence") {
    val options = DashboardSpoolOptions(Files.createTempDirectory("stove-spool-quota"), maxBytes = 1024 * 1024)
    DashboardSpool(DashboardIngestion.Grpc(), options).use { spool ->
      spool.append(spoolEvent(payloadSize = 220_000))
      val before = spool.peek().single().event
      shouldThrow<DashboardSpoolException> { spool.append(spoolEvent(payloadSize = 220_000)) }
      spool.status().pendingEvents shouldBe 1
      spool.peek().single().event shouldBe before
      spool.acknowledge(spool.peek())
      spool.append(spoolEvent())
      spool.peek().single().event.sequence shouldBe 2
      (Files.size(spool.path) <= options.maxBytes) shouldBe true
    }
  }

  test("batches have count byte and single run bounds") {
    val options = DashboardSpoolOptions(Files.createTempDirectory("stove-spool-bounds"))
    DashboardSpool(DashboardIngestion.Grpc(), options).use { spool ->
      repeat(101) { spool.append(spoolEvent()) }
      spool.peek().size shouldBe 100
      spool.acknowledge(spool.peek())
      spool.append(spoolEvent(run = "other"))
      spool.peek().size shouldBe 1
      spool.acknowledge(spool.peek())
      spool.acknowledge(spool.peek())
      repeat(4) { spool.append(spoolEvent(payloadSize = 400_000)) }
      val batch = spool.peek()
      batch.size shouldBe 2
      (DashboardEventBatch.newBuilder().addAllEvents(batch.map { it.event }).build().serializedSize <= MAX_BATCH_BYTES) shouldBe true
    }
  }
})

internal fun spoolEvent(run: String = "run", payloadSize: Int = 0): DashboardEvent = DashboardEvent.newBuilder()
  .setRunId(run)
  .setRunStarted(RunStartedEvent.newBuilder().setAppName("app" + "x".repeat(payloadSize)))
  .build()

private fun runSpoolProcess(directory: java.nio.file.Path, mode: String = "crash"): List<String> {
  val classpath = listOf(
    SpoolProcessFixture::class.java,
    DashboardSpool::class.java,
    DashboardEvent::class.java,
    com.google.protobuf.Message::class.java,
    org.sqlite.JDBC::class.java,
    org.slf4j.LoggerFactory::class.java,
    Unit::class.java
  ).map { java.nio.file.Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
    .distinct().joinToString(java.io.File.pathSeparator)
  val process = ProcessBuilder(
    java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
    "-cp",
    classpath,
    SpoolProcessFixture::class.java.name,
    directory.toString(),
    mode
  ).redirectError(directory.resolve("child-error.log").toFile()).start()
  try {
    process.waitFor(30, TimeUnit.SECONDS) shouldBe true
    val error = Files.readString(directory.resolve("child-error.log"))
    check(process.exitValue() == 0) { error }
    return process.inputStream.bufferedReader().readLines()
  } finally {
    process.destroyForcibly()
  }
}
