package com.trendyol.stove.portal

import com.trendyol.stove.portal.api.*
import com.trendyol.stove.portal.api.PortalEventServiceGrpcKt.PortalEventServiceCoroutineImplBase
import io.grpc.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.CopyOnWriteArrayList

class PortalEmitterTest :
  FunSpec({

    test("emits events to a running gRPC server") {
      val received = CopyOnWriteArrayList<PortalEvent>()
      val server = startMockServer(received, port = 0)
      val port = server.port

      try {
        val emitter = PortalEmitter("localhost", port)
        val event = PortalEvent.newBuilder()
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
      val emitter = PortalEmitter("localhost", 1, maxFailures = 2)

      // These should not throw
      repeat(10) {
        emitter.tryEmit(
          PortalEvent.newBuilder()
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
  })

private fun startMockServer(received: MutableList<PortalEvent>, port: Int): Server {
  val service = object : PortalEventServiceCoroutineImplBase() {
    override suspend fun sendEvent(request: PortalEvent): EventAck {
      received.add(request)
      return EventAck.newBuilder().setAccepted(true).build()
    }

    override suspend fun streamEvents(requests: Flow<PortalEvent>): EventAck {
      requests.collect { received.add(it) }
      return EventAck.newBuilder().setAccepted(true).build()
    }
  }

  return ServerBuilder.forPort(port)
    .addService(service)
    .build()
    .start()
}
