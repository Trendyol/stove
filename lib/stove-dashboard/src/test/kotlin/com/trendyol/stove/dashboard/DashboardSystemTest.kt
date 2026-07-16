package com.trendyol.stove.dashboard

import com.trendyol.stove.dashboard.api.*
import com.trendyol.stove.dashboard.api.DashboardEventServiceGrpcKt.DashboardEventServiceCoroutineImplBase
import com.trendyol.stove.interactions.InteractionAttribution
import com.trendyol.stove.interactions.MockInteraction
import com.trendyol.stove.interactions.MockInteractionListener
import com.trendyol.stove.interactions.MockInteractionPublisher
import com.trendyol.stove.interactions.MockWarning
import com.trendyol.stove.interactions.MockWarningKind
import com.trendyol.stove.interactions.MockWarningListener
import com.trendyol.stove.interactions.MockWarningPublisher
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
import java.time.Instant
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

  test("mock interaction forwarding preserves diagnostic metadata") {
    val received = CopyOnWriteArrayList<DashboardEvent>()
    val server = startMockServer(received, port = 0)

    try {
      val stove = Stove()
      val system = DashboardSystem(
        stove,
        DashboardSystemOptions(appName = "test-api", cliPort = server.port)
      )
      system.run()
      system.onInteraction(
        MockInteraction(
          system = "WireMock",
          protocol = MockInteraction.Protocol.HTTP,
          method = "POST",
          target = "/payments",
          matched = true,
          stubId = "stub-1",
          testId = "test-1",
          attribution = InteractionAttribution.PROVEN_STUB,
          requestBody = """{"amount":100}""",
          requestBodyTruncated = false,
          responseBody = """{"ok":true}""",
          responseBodyTruncated = false,
          status = "200",
          latencyMs = 42,
          nearMisses = emptyList(),
          traceId = "0123456789abcdef0123456789abcdef",
          timestamp = Instant.parse("2026-01-01T00:00:00Z"),
          scenarioName = "payment retry",
          scenarioState = "attempt-2",
          nextScenarioState = "recovered",
          configuredDelayMs = 250,
          fault = "CONNECTION_RESET_BY_PEER",
          clientDeadlineMs = 500
        )
      )
      delay(300.milliseconds)
      system.stop()

      val event = received.first { it.hasMockInteraction() }.mockInteraction
      event.scenarioName shouldBe "payment retry"
      event.scenarioState shouldBe "attempt-2"
      event.nextScenarioState shouldBe "recovered"
      event.configuredDelayMs shouldBe 250
      event.fault shouldBe "CONNECTION_RESET_BY_PEER"
      event.clientDeadlineMs shouldBe 500
    } finally {
      server.shutdownNow()
    }
  }

  test("mock diagnostics stay inside the dashboard run lifecycle") {
    val received = CopyOnWriteArrayList<DashboardEvent>()
    val server = startMockServer(received, port = 0)

    try {
      val stove = Stove()
      stove.getOrRegister(LifecycleDiagnosticSystem(stove))
      val system = DashboardSystem(
        stove,
        DashboardSystemOptions(appName = "test-api", cliPort = server.port)
      )

      system.run()
      stove.startTest(StoveTestContext("test-open", "open test", "LifecycleSpec"))
      system.stop()
      delay(500.milliseconds)

      received.first().hasRunStarted() shouldBe true
      received.any { it.hasMockInteraction() && it.mockInteraction.target == "/on-register" } shouldBe true
      received.any { it.hasMockWarning() && it.mockWarning.target == "/on-register" } shouldBe true
      received.none {
        it.hasMockInteraction() && it.mockInteraction.target == "/during-finalization"
      } shouldBe true
      received.none { it.hasMockWarning() && it.mockWarning.target == "/during-finalization" } shouldBe true
      received.last().hasRunEnded() shouldBe true
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

private class LifecycleDiagnosticSystem(
  override val stove: Stove
) : PluggedSystem,
  Reports,
  MockInteractionPublisher,
  MockWarningPublisher {
  private val interactionListeners = CopyOnWriteArrayList<MockInteractionListener>()
  private val warningListeners = CopyOnWriteArrayList<MockWarningListener>()

  override val reportSystemName: String = "Lifecycle diagnostics"

  override fun addInteractionListener(listener: MockInteractionListener) {
    interactionListeners.add(listener)
    listener.onInteraction(interaction("/on-register"))
  }

  override fun removeInteractionListener(listener: MockInteractionListener) {
    interactionListeners.remove(listener)
  }

  override fun addWarningListener(listener: MockWarningListener) {
    warningListeners.add(listener)
    listener.onWarning(warning("/on-register"))
  }

  override fun removeWarningListener(listener: MockWarningListener) {
    warningListeners.remove(listener)
  }

  override fun snapshot(): SystemSnapshot {
    interactionListeners.forEach { it.onInteraction(interaction("/during-finalization")) }
    warningListeners.forEach { it.onWarning(warning("/during-finalization")) }
    return SystemSnapshot(
      system = reportSystemName,
      state = emptyMap<String, Any>(),
      summary = "lifecycle snapshot"
    )
  }

  override fun close() = Unit

  private fun interaction(target: String) = MockInteraction(
    system = reportSystemName,
    protocol = MockInteraction.Protocol.HTTP,
    method = "GET",
    target = target,
    matched = true,
    stubId = null,
    testId = null,
    attribution = InteractionAttribution.UNATTRIBUTED,
    requestBody = "",
    requestBodyTruncated = false,
    responseBody = "",
    responseBodyTruncated = false,
    status = "200",
    latencyMs = null,
    nearMisses = emptyList(),
    traceId = null,
    timestamp = Instant.now()
  )

  private fun warning(target: String) = MockWarning(
    system = reportSystemName,
    kind = MockWarningKind.UNUSED_STUB,
    testId = null,
    message = "lifecycle warning",
    target = target
  )
}
