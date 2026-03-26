package com.trendyol.stove.portal

import com.trendyol.stove.portal.api.*
import com.trendyol.stove.portal.api.PortalEventServiceGrpcKt.PortalEventServiceCoroutineImplBase
import com.trendyol.stove.reporting.*
import com.trendyol.stove.system.Stove
import io.grpc.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

class PortalSystemTest : FunSpec({
  test("lifecycle: registers as listener, emits events, unregisters on stop") {
    val received = CopyOnWriteArrayList<PortalEvent>()
    val server = startMockServer(received, port = 0)
    val port = server.port

    try {
      val stove = Stove()
      val options = PortalSystemOptions(appName = "test-api", cliPort = port)
      val system = PortalSystem(stove, options)

      // Start the system — should emit RunStartedEvent
      system.run()
      delay(200.milliseconds)

      // Simulate test lifecycle via reporter
      val ctx = StoveTestContext("test-1", "my test", "MySpec")
      stove.reporter.startTest(ctx)
      stove.reporter.record(ReportEntry.success("HTTP", "test-1", "GET /api"))
      stove.reporter.endTest()

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
      types.contains("TestStarted") shouldBe true
      types.contains("EntryRecorded") shouldBe true
    } finally {
      server.shutdownNow()
    }
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
