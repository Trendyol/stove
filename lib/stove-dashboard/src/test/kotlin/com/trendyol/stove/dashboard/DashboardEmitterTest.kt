package com.trendyol.stove.dashboard

import com.trendyol.stove.dashboard.api.*
import com.trendyol.stove.dashboard.api.DashboardEventServiceGrpcKt.DashboardEventServiceCoroutineImplBase
import io.grpc.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DashboardEmitterTest :
  FunSpec({

    test("emits events to a running gRPC server") {
      val received = CopyOnWriteArrayList<DashboardEvent>()
      val server = startMockServer(received, port = 0)
      val port = server.port

      try {
        val emitter = DashboardEmitter("localhost", port)
        val event = DashboardEvent.newBuilder()
          .setRunId("run-1")
          .setRunStarted(
            RunStartedEvent.newBuilder()
              .setAppName("test-app")
              .build()
          )
          .build()

        emitter.tryEmit(event)
        emitter.tryEmit(event)

        // Wait for async drain
        delay(500)
        emitter.close()

        received.size shouldBe 2
        received[0].runId shouldBe "run-1"
      } finally {
        server.shutdownNow()
      }
    }

    test("auto-disables after consecutive failures without throwing") {
      // Connect to a port that is not listening
      val emitter = DashboardEmitter("localhost", 1, maxFailures = 2)

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
      delay(2000)
      emitter.close()

      // If we get here without exception, the test passes
    }

    test("does not drop burst events while receiver is temporarily blocked") {
      val received = CopyOnWriteArrayList<DashboardEvent>()
      val firstRequestStarted = CountDownLatch(1)
      val releaseFirstRequest = CountDownLatch(1)
      val server = startMockServer(received, port = 0) {
        if (firstRequestStarted.count > 0) {
          firstRequestStarted.countDown()
          releaseFirstRequest.await(5, TimeUnit.SECONDS)
        }
      }
      val port = server.port

      try {
        val emitter = DashboardEmitter("localhost", port)
        val totalEvents = 700

        repeat(totalEvents) { index ->
          emitter.tryEmit(runStartedEvent(index))
        }

        firstRequestStarted.await(2, TimeUnit.SECONDS) shouldBe true
        releaseFirstRequest.countDown()

        delay(500)
        emitter.close()

        received.size shouldBe totalEvents
      } finally {
        server.shutdownNow()
      }
    }

    test("close drains queued events before shutting down") {
      val received = CopyOnWriteArrayList<DashboardEvent>()
      val server = startMockServer(received, port = 0) {
        delay(12)
      }
      val port = server.port

      try {
        val emitter = DashboardEmitter("localhost", port)
        val totalEvents = 350

        repeat(totalEvents) { index ->
          emitter.tryEmit(runStartedEvent(index))
        }

        emitter.close()

        received.size shouldBe totalEvents
      } finally {
        server.shutdownNow()
      }
    }
  })

private fun startMockServer(
  received: MutableList<DashboardEvent>,
  port: Int,
  beforeAck: suspend (DashboardEvent) -> Unit = {}
): Server {
  val service = object : DashboardEventServiceCoroutineImplBase() {
    override suspend fun sendEvent(request: DashboardEvent): EventAck {
      beforeAck(request)
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

private fun runStartedEvent(index: Int): DashboardEvent =
  DashboardEvent.newBuilder()
    .setRunId("run-$index")
    .setRunStarted(
      RunStartedEvent.newBuilder()
        .setAppName("test-app")
        .build()
    )
    .build()
