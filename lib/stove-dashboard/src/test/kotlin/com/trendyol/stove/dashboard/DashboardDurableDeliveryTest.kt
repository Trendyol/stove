package com.trendyol.stove.dashboard

import com.trendyol.stove.dashboard.api.BatchAck
import com.trendyol.stove.dashboard.api.DashboardEvent
import com.trendyol.stove.dashboard.api.DashboardEventBatch
import com.trendyol.stove.dashboard.api.DashboardEventServiceGrpcKt.DashboardEventServiceCoroutineImplBase
import com.trendyol.stove.dashboard.api.EventAck
import io.grpc.ServerBuilder
import io.grpc.Status
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class DashboardDurableDeliveryTest : FunSpec({
  test("HTTP delivery uses committed batch acknowledgments") {
    val received = CopyOnWriteArrayList<DashboardEvent>()
    val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/api/v1/events/batch") { exchange ->
      val batch = DashboardEventBatch.parseFrom(exchange.requestBody.readBytes())
      received.addAll(batch.eventsList)
      val response = batchAck(batch).toByteArray()
      exchange.sendResponseHeaders(200, response.size.toLong())
      exchange.responseBody.use { it.write(response) }
    }
    server.start()
    try {
      val emitter = isolatedEmitter(DashboardIngestion.Http("http://127.0.0.1:${server.address.port}"))
      repeat(101) { emitter.tryEmit(spoolEvent()) }
      emitter.close()
      emitter.deliveryStatus.pendingEvents shouldBe 0
      received.map { it.sequence } shouldBe (1L..101L).toList()
    } finally {
      server.stop(0)
    }
  }

  test("restart replays the original batch after the server committed but lost its acknowledgment") {
    val fail = AtomicBoolean(true)
    val committed = ConcurrentHashMap<String, DashboardEvent>()
    val attempts = CopyOnWriteArrayList<DashboardEvent>()
    val server = ServerBuilder.forPort(0).addService(object : DashboardEventServiceCoroutineImplBase() {
      override suspend fun sendBatch(request: DashboardEventBatch): BatchAck {
        (request.eventsCount <= MAX_BATCH_EVENTS) shouldBe true
        (request.serializedSize <= MAX_BATCH_BYTES) shouldBe true
        attempts.addAll(request.eventsList)
        request.eventsList.forEach { committed.putIfAbsent(it.eventId, it) }
        if (fail.get()) throw Status.UNAVAILABLE.asException()
        return batchAck(request)
      }
    }).build().start()
    val endpoint = DashboardIngestion.Grpc(port = server.port)
    val options = DashboardSpoolOptions(Files.createTempDirectory("stove-delivery-restart"))
    try {
      val first = DashboardEmitter(endpoint, maxFailures = 1, spoolOptions = options)
      repeat(100) { first.tryEmit(spoolEvent()) }
      first.close()
      first.deliveryStatus.pendingEvents shouldBe 100
      val original = committed.values.toList()
      fail.set(false)
      val second = DashboardEmitter(endpoint, spoolOptions = options)
      second.tryEmit(spoolEvent())
      second.close()
      second.deliveryStatus.pendingEvents shouldBe 0
      committed.size shouldBe 101
      committed.values.map { it.sequence }.sorted() shouldBe (1L..101L).toList()
      original.forEach { event ->
        (attempts.count { it == event } >= 2) shouldBe true
      }
    } finally {
      server.shutdownNow()
    }
  }

  test("malformed batch acknowledgments leave every record pending") {
    val server = ServerBuilder.forPort(0).addService(object : DashboardEventServiceCoroutineImplBase() {
      override suspend fun sendBatch(request: DashboardEventBatch): BatchAck = BatchAck.getDefaultInstance()
    }).build().start()
    try {
      val emitter = DashboardEmitter(
        DashboardIngestion.Grpc(port = server.port),
        maxFailures = 1,
        spoolOptions = DashboardSpoolOptions(Files.createTempDirectory("stove-delivery-malformed"))
      )
      emitter.tryEmit(spoolEvent())
      emitter.close()
      emitter.deliveryStatus.pendingEvents shouldBe 1
    } finally {
      server.shutdownNow()
    }
  }
})

internal fun batchAck(batch: DashboardEventBatch): BatchAck = BatchAck.newBuilder()
  .addAllAcknowledgements(
    batch.eventsList.map { event ->
      EventAck.newBuilder().setAccepted(true).setEventId(event.eventId).setSequence(event.sequence).build()
    }
  ).build()
