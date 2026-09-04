package com.trendyol.stove.dashboard

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.trendyol.stove.dashboard.api.DashboardEvent
import com.trendyol.stove.dashboard.api.EventAck
import com.trendyol.stove.dashboard.api.RunStartedEvent
import com.trendyol.stove.system.Stove
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class HttpDashboardIngestionTest :
  FunSpec({

    test("dashboard system selects HTTP ingestion explicitly") {
      val received = CopyOnWriteArrayList<DashboardEvent>()
      val server = startMockServer(received = received)

      try {
        val system = DashboardSystem(
          Stove(),
          DashboardSystemOptions(
            appName = "test-app",
            ingestion = DashboardIngestion.Http(server.baseUrl)
          )
        )

        system.run()
        system.stop()

        received.first().hasRunStarted() shouldBe true
        received.last().hasRunEnded() shouldBe true
      } finally {
        server.stop(0)
      }
    }

    test("gRPC ingestion has local defaults and validates its endpoint") {
      DashboardSystemOptions(appName = "test-app").ingestion shouldBe DashboardIngestion.Grpc()
      shouldThrow<IllegalArgumentException> {
        DashboardIngestion.Grpc(host = "")
      }
      shouldThrow<IllegalArgumentException> {
        DashboardIngestion.Grpc(port = 0)
      }
    }

    test("HTTP ingestion requires an absolute HTTP(S) base URL") {
      shouldThrow<IllegalArgumentException> {
        DashboardIngestion.Http("ftp://stove.internal")
      }
      shouldThrow<IllegalArgumentException> {
        DashboardIngestion.Http("/relative/path")
      }
    }

    test("emits events to a running HTTP server") {
      val received = CopyOnWriteArrayList<DashboardEvent>()
      val server = startMockServer(received = received)

      try {
        val emitter = DashboardEmitter(DashboardIngestion.Http(server.baseUrl))
        val event = DashboardEvent.newBuilder()
          .setRunId("run-1")
          .setRunStarted(RunStartedEvent.newBuilder().setAppName("test-app").build())
          .build()

        emitter.tryEmit(event)
        emitter.tryEmit(event)

        // Wait for async drain
        delay(500.milliseconds)
        emitter.close()

        received.size shouldBe 2
        received[0].runId shouldBe "run-1"
        received.map { it.sequence } shouldBe listOf(1L, 2L)
        received.map { it.eventId }.toSet().size shouldBe 2
      } finally {
        server.stop(0)
      }
    }

    test("auto-disables after consecutive failures without throwing") {
      // Connect to a port that is not listening
      val emitter = DashboardEmitter(
        DashboardIngestion.Http("http://localhost:1"),
        maxFailures = 2
      )

      // These should not throw
      repeat(10) {
        emitter.tryEmit(
          DashboardEvent.newBuilder()
            .setRunId("run-1")
            .setRunStarted(RunStartedEvent.newBuilder().setAppName("test").build())
            .build()
        )
      }

      // Wait for the drain loop to process and fail
      delay(2000.milliseconds)
      emitter.close()

      // If we get here without exception, the test passes
    }

    test("keeps emitting after per-event validation rejections (HTTP 400)") {
      // The server rejects individual events (e.g. unknown test `default`) with 400.
      // These are per-event validation errors, not transport outages, so they must NOT trip
      // the consecutive-failure auto-disable. A subsequent valid event must still be delivered.
      val received = CopyOnWriteArrayList<DashboardEvent>()
      val server = startMockServer(received = received) { event ->
        if (event.runId.startsWith("bad-")) 400 else 200
      }

      try {
        val emitter = DashboardEmitter(DashboardIngestion.Http(server.baseUrl), maxFailures = 2)

        // More consecutive rejections than maxFailures — must not disable the emitter.
        repeat(5) { index -> emitter.tryEmit(runStartedEvent(index).toBuilder().setRunId("bad-$index").build()) }
        emitter.tryEmit(runStartedEvent(99).toBuilder().setRunId("good-99").build())

        delay(1000.milliseconds)
        emitter.close()

        received.map { it.runId } shouldContain "good-99"
      } finally {
        server.stop(0)
      }
    }

    test("auto-disables on consecutive server failures (HTTP 500)") {
      val attempts = AtomicInteger(0)
      val received = CopyOnWriteArrayList<DashboardEvent>()
      val server = startMockServer(received = received) {
        attempts.incrementAndGet()
        500
      }

      try {
        val emitter = DashboardEmitter(
          DashboardIngestion.Http(server.baseUrl),
          maxFailures = 3
        )

        repeat(10) { index -> emitter.tryEmit(runStartedEvent(index)) }

        delay(1500.milliseconds)
        emitter.close()

        // Once disabled after maxFailures, no further events are attempted against the server.
        attempts.get() shouldBe 3
      } finally {
        server.stop(0)
      }
    }

    test("close drains queued events before shutting down") {
      val received = CopyOnWriteArrayList<DashboardEvent>()
      val server = startMockServer(received = received) {
        Thread.sleep(12)
        200
      }

      try {
        val emitter = DashboardEmitter(DashboardIngestion.Http(server.baseUrl))
        val totalEvents = 350

        repeat(totalEvents) { index ->
          emitter.tryEmit(runStartedEvent(index))
        }

        emitter.close()

        received.size shouldBe totalEvents
      } finally {
        server.stop(0)
      }
    }
  })

private class MockHttpServer(val server: HttpServer, val baseUrl: String) {
  fun stop(delay: Int) = server.stop(delay)
}

private fun startMockServer(
  received: MutableList<DashboardEvent>,
  respond: (DashboardEvent) -> Int = { 200 }
): MockHttpServer {
  val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
  server.createContext("/api/v1/events") { exchange: HttpExchange ->
    val event = DashboardEvent.parseFrom(exchange.requestBody.readBytes())
    val status = respond(event)
    if (status == 200) {
      received.add(event)
      val ack = EventAck.newBuilder()
        .setAccepted(true)
        .setEventId(event.eventId)
        .setSequence(event.sequence)
        .build()
      exchange.responseHeaders.add("Content-Type", "application/x-protobuf")
      exchange.sendResponseHeaders(200, ack.toByteArray().size.toLong())
      exchange.responseBody.use { it.write(ack.toByteArray()) }
    } else {
      val body = "rejected".toByteArray()
      exchange.sendResponseHeaders(status, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
  }
  server.start()
  return MockHttpServer(server, "http://127.0.0.1:${server.address.port}")
}

private fun runStartedEvent(index: Int): DashboardEvent =
  DashboardEvent.newBuilder()
    .setRunId("run-$index")
    .setRunStarted(
      RunStartedEvent.newBuilder()
        .setAppName("test-app")
        .build()
    )
    .build()
