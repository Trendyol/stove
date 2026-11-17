package com.trendyol.stove.dashboard

import com.trendyol.stove.dashboard.api.*
import com.trendyol.stove.dashboard.api.DashboardEventServiceGrpcKt.DashboardEventServiceCoroutineImplBase
import com.trendyol.stove.interactions.*
import com.trendyol.stove.reporting.*
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.tracing.*
import io.grpc.ServerBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class DashboardDeliveryTest : FunSpec({
  test("all source channels survive immediate test and application shutdown") {
    DeliveryFixture().use { fixture ->
      fixture.exercise()
      fixture.verify()
    }
  }
})

private class DeliveryFixture : AutoCloseable {
  private val received = CopyOnWriteArrayList<DashboardEvent>()
  private val server = ServerBuilder.forPort(0).addService(object : DashboardEventServiceCoroutineImplBase() {
    override suspend fun sendEvent(request: DashboardEvent): EventAck {
      received += request
      return EventAck.newBuilder().setAccepted(true).build()
    }
  }).build().start()
  private val stove = Stove().with {
    dashboard { DashboardSystemOptions("delivery", ingestion = DashboardIngestion.Grpc(port = server.port)) }
  }
  private val publisher = stove.getOrRegister(DeliveryPublisher(stove))

  suspend fun exercise() {
    stove.applicationUnderTest(object : ApplicationUnderTest<Unit> {
      override suspend fun start(configurations: List<String>) = publisher.emit("startup")
      override suspend fun stop() = publisher.emit("app-stop")
    }).run()
    stove.startTest(StoveTestContext("test", "test"))
    publisher.emit("test")
    stove.recordReport(ReportEntry.failure("Delivery", "test", "attempt", "failed"))
    Stove.reporter().reportFailure("test failed")
    stove.endTest()
    stove.close()
  }

  fun verify() {
    val stages = listOf("startup", "test", "app-stop", "cleanup")
    received.filter { it.hasSpanRecorded() }.map { it.spanRecorded.operationName } shouldBe stages
    received.filter { it.hasMockInteraction() }.map { it.mockInteraction.target } shouldBe stages
    received.filter { it.hasMockWarning() }.map { it.mockWarning.message } shouldBe stages
    received.count { it.hasTestStarted() } shouldBe 1
    received.count { it.hasEntryRecorded() } shouldBe 1
    received.single { it.hasTestEnded() }.testEnded.status shouldBe "FAILED"
    received.filter { it.hasSnapshot() }.map { it.snapshot.trigger } shouldBe listOf("FAILURE", "TEST_END")
    received.filter { it.hasSnapshot() }.all { it.snapshot.stateJson == """{"closed":false}""" } shouldBe true
    received.first().hasRunStarted() shouldBe true
    received.last().hasRunEnded() shouldBe true
    received.map { it.sequence } shouldBe (1L..received.size.toLong()).toList()
    received.map { it.eventId }.distinct().size shouldBe received.size
  }

  override fun close() {
    stove.close()
    server.shutdownNow()
  }
}

private class DeliveryPublisher(override val stove: Stove) :
  PluggedSystem,
  Reports,
  SpanListenerRegistry,
  MockInteractionPublisher,
  MockWarningPublisher {
  private val spans = CopyOnWriteArrayList<SpanEventListener>()
  private val interactions = MockInteractionListeners()
  private val warnings = MockWarningListeners()
  private var closed = false
  override val reportSystemName = "Delivery"
  override fun addSpanListener(listener: SpanEventListener) {
    spans += listener
  }
  override fun removeSpanListener(listener: SpanEventListener) {
    spans -= listener
  }
  override fun addInteractionListener(listener: MockInteractionListener) = interactions.add(listener)
  override fun removeInteractionListener(listener: MockInteractionListener) = interactions.remove(listener)
  override fun addWarningListener(listener: MockWarningListener) = warnings.add(listener)
  override fun removeWarningListener(listener: MockWarningListener) = warnings.remove(listener)
  override fun snapshot() = SystemSnapshot(reportSystemName, mapOf("closed" to closed), "")

  fun emit(stage: String) {
    spans.forEach { it.onSpanRecorded(SpanInfo("trace", stage, "", stage, "app", 1, 2, SpanStatus.OK)) }
    warnings.emit(MockWarning(reportSystemName, MockWarningKind.UNUSED_STUB, "test", stage))
    interactions.emit(interaction(stage))
  }

  override fun close() {
    emit("cleanup")
    closed = true
  }

  private fun interaction(stage: String) = MockInteraction(
    system = reportSystemName, protocol = MockInteraction.Protocol.HTTP, method = "GET", target = stage,
    matched = true, stubId = "stub", testId = "test", attribution = InteractionAttribution.PROVEN_STUB,
    requestBody = "", requestBodyTruncated = false, responseBody = "", responseBodyTruncated = false,
    status = "200", latencyMs = 1, nearMisses = emptyList(), traceId = "trace", timestamp = Instant.now()
  )
}
