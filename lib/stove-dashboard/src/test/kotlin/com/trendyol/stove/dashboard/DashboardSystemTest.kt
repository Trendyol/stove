package com.trendyol.stove.dashboard

import com.trendyol.stove.dashboard.api.*
import com.trendyol.stove.dashboard.api.DashboardEventServiceGrpcKt.DashboardEventServiceCoroutineImplBase
import com.trendyol.stove.reporting.*
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.PluggedSystem
import io.grpc.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class DashboardSystemTest : FunSpec({
  test("lifecycle: registers as listener, emits events, unregisters on stop") {
    val received = CopyOnWriteArrayList<DashboardEvent>()
    val server = startMockServer(received, port = 0)
    val port = server.port

    try {
      val stove = Stove()
      val options = DashboardSystemOptions(appName = "test-api", cliPort = port)
      val system = DashboardSystem(stove, options)

      // Start the system — should emit RunStartedEvent
      system.run()
      delay(200.milliseconds)

      // Simulate test lifecycle via reporter
      val ctx = StoveTestContext("test-1", "my test", "MySpec")
      stove.startTest(ctx)
      stove.recordReport(ReportEntry.success("HTTP", "test-1", "GET /api"))
      stove.endTest()

      // Wait for async events to be processed before stopping
      delay(1000.milliseconds)

      // Stop the system — should emit RunEndedEvent
      system.stop()

      // Wait for close to drain
      delay(1000.milliseconds)

      // Verify we received the key lifecycle events
      val types = received.map {
        when {
          it.hasRunStarted() -> "RunStarted"
          it.hasTestStarted() -> "TestStarted"
          it.hasEntryRecorded() -> "EntryRecorded"
          it.hasTestEnded() -> "TestEnded"
          it.hasRunEnded() -> "RunEnded"
          else -> "Unknown"
        }
      }
      types.contains("RunStarted") shouldBe true
      received.first { it.hasRunStarted() }.runStarted.appName shouldBe "test-api"
      received.first { it.hasRunStarted() }.runStarted.stoveVersion shouldBe StoveCompatibilityVersion.VALUE
      types.contains("TestStarted") shouldBe true
      types.contains("EntryRecorded") shouldBe true
    } finally {
      server.shutdownNow()
    }
  }

  test("stop finalizes tests still marked running") {
    val received = CopyOnWriteArrayList<DashboardEvent>()
    val server = startMockServer(received, port = 0)
    val port = server.port

    try {
      val stove = Stove()
      val options = DashboardSystemOptions(appName = "test-api", cliPort = port)
      val system = DashboardSystem(stove, options)

      system.run()
      delay(200.milliseconds)

      stove.startTest(StoveTestContext("test-still-running", "still running", "MySpec"))
      stove.recordReport(ReportEntry.success("HTTP", "test-still-running", "GET /health"))

      delay(300.milliseconds)
      system.stop()
      delay(1000.milliseconds)

      received.any { it.hasTestEnded() && it.testEnded.testId == "test-still-running" } shouldBe true
      received.first { it.hasRunEnded() }.runEnded.totalTests shouldBe 1
    } finally {
      server.shutdownNow()
    }
  }

  test("stop does not re-finalize a test whose end callback is already in progress") {
    val received = CopyOnWriteArrayList<DashboardEvent>()
    val server = startMockServer(received, port = 0)
    val port = server.port

    try {
      val stove = Stove()
      val snapshotSystem = BlockingSnapshotSystem(stove)
      stove.getOrRegister(snapshotSystem)

      val options = DashboardSystemOptions(appName = "test-api", cliPort = port)
      val system = DashboardSystem(stove, options)

      system.run()
      delay(200.milliseconds)

      stove.startTest(StoveTestContext("test-race", "race", "MySpec"))

      val endJob = async(Dispatchers.Default) {
        system.onTestEnded("test-race")
      }

      snapshotSystem.awaitFirstSnapshotCall() shouldBe true

      val stopJob = async(Dispatchers.Default) {
        system.stop()
      }

      try {
        snapshotSystem.awaitSecondSnapshotCall() shouldBe false
      } finally {
        snapshotSystem.releaseSnapshots()
        endJob.await()
        stopJob.await()
      }

      delay(1000.milliseconds)

      received.count { it.hasTestEnded() && it.testEnded.testId == "test-race" } shouldBe 1
      received.first { it.hasRunEnded() }.runEnded.totalTests shouldBe 1
      received.first { it.hasRunEnded() }.runEnded.passed shouldBe 1
      received.first { it.hasRunEnded() }.runEnded.failed shouldBe 0
    } finally {
      server.shutdownNow()
    }
  }
})

private fun startMockServer(received: MutableList<DashboardEvent>, port: Int): Server {
  val service = object : DashboardEventServiceCoroutineImplBase() {
    override suspend fun sendEvent(request: DashboardEvent): EventAck {
      received.add(request)
      return EventAck.newBuilder().setAccepted(true).build()
    }

    override suspend fun streamEvents(requests: Flow<DashboardEvent>): EventAck {
      requests.collect { received.add(it) }
      return EventAck.newBuilder().setAccepted(true).build()
    }
  }

  return ServerBuilder.forPort(port)
    .addService(service)
    .build()
    .start()
}

private class BlockingSnapshotSystem(
  override val stove: Stove
) : PluggedSystem,
  Reports {
  private val snapshotCalls = AtomicInteger(0)
  private val firstSnapshotCall = CountDownLatch(1)
  private val secondSnapshotCall = CountDownLatch(1)
  private val releaseSnapshots = CountDownLatch(1)

  override fun snapshot(): SystemSnapshot {
    when (snapshotCalls.incrementAndGet()) {
      1 -> {
        firstSnapshotCall.countDown()
        releaseSnapshots.await(5, TimeUnit.SECONDS)
      }

      2 -> secondSnapshotCall.countDown()
    }

    return SystemSnapshot(
      system = "BlockingSnapshot",
      state = emptyMap<String, Any>(),
      summary = "blocking snapshot"
    )
  }

  fun awaitFirstSnapshotCall(): Boolean = firstSnapshotCall.await(2, TimeUnit.SECONDS)

  fun awaitSecondSnapshotCall(): Boolean = secondSnapshotCall.await(1, TimeUnit.SECONDS)

  fun releaseSnapshots() {
    releaseSnapshots.countDown()
  }

  override fun close() = Unit
}
