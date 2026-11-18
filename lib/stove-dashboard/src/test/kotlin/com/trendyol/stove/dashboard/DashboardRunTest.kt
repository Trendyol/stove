package com.trendyol.stove.dashboard

import arrow.core.getOrElse
import com.trendyol.stove.dashboard.api.*
import com.trendyol.stove.dashboard.api.DashboardEventServiceGrpcKt.DashboardEventServiceCoroutineImplBase
import com.trendyol.stove.reporting.*
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.*
import io.grpc.ServerBuilder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.CopyOnWriteArrayList

class DashboardRunTest : FunSpec({
  test("captures full inventory and cleanup before ending a failed startup run") {
    RunFixture().use { fixture ->
      shouldThrow<IllegalStateException> { fixture.stove.run() }
      fixture.quiet.metadataReads shouldBe 1
      val events = fixture.received
      events.first().hasRunStarted() shouldBe true
      events.last().runEnded.lifecycleFailed shouldBe true
      events.last().runEnded.failed shouldBe 0
      val inventory = events.first { it.hasRunEnvironment() }.runEnvironment
      inventory.componentsList.size shouldBe 3
      inventory.componentsList.last().role shouldBe "APPLICATION"
      inventory.componentsList.all { it.phasesCount == 0 } shouldBe true
      val terminal = events.last { it.hasRunEnvironment() }.runEnvironment
      terminal.componentsList.first { it.role == "APPLICATION" }.phasesList
        .map { it.status } shouldBe listOf("FAILED", "SUCCEEDED")
      events.map { it.runId }.distinct() shouldBe listOf(fixture.stove.events.runId)
      events.map { it.sequence } shouldBe (1L..events.size.toLong()).toList()
    }
  }

  test("inventory failure still produces a failed dashboard run") {
    RunFixture(metadataFailure = true).use { fixture ->
      shouldThrow<IllegalStateException> { fixture.stove.run() }.message shouldBe "metadata failed"
      val inventory = fixture.received.first { it.hasRunEnvironment() }.runEnvironment
      inventory.componentsList.map { it.name } shouldBe listOf("DashboardSystem", "QuietSystem", "FailingApplication")
      fixture.received.first().hasRunStarted() shouldBe true
      fixture.received.last().runEnded.lifecycleFailed shouldBe true
    }
  }

  test("preparation failure is exported even before dashboard run is called") {
    RunFixture(prepareFailure = true).use { fixture ->
      shouldThrow<IllegalStateException> { fixture.stove.run() }
      fixture.received.first().hasRunStarted() shouldBe true
      fixture.received.last().runEnded.lifecycleFailed shouldBe true
      val eventCount = fixture.received.size
      fixture.stove.getOrNone<DashboardSystem>().onSome { it.run() }
      fixture.received.size shouldBe eventCount
    }
  }
})

private class RunFixture(prepareFailure: Boolean = false, metadataFailure: Boolean = false) : AutoCloseable {
  val received = CopyOnWriteArrayList<DashboardEvent>()
  private val server = ServerBuilder.forPort(0).addService(object : DashboardEventServiceCoroutineImplBase() {
    override suspend fun sendEvent(request: DashboardEvent): EventAck {
      received += request
      return EventAck.newBuilder().setAccepted(true).build()
    }

    override suspend fun streamEvents(requests: Flow<DashboardEvent>): EventAck {
      requests.collect { received += it }
      return EventAck.newBuilder().setAccepted(true).build()
    }
  }).build().start()
  val stove = Stove().with {
    dashboard { DashboardSystemOptions(appName = "inventory-test", ingestion = DashboardIngestion.Grpc(port = server.port)) }
  }.apply {
    getOrRegister(QuietSystem(this, prepareFailure, metadataFailure))
    applicationUnderTest(FailingApplication())
  }

  val quiet = stove.getOrNone<QuietSystem>().getOrElse { error("Missing system") }

  override fun close() {
    stove.close()
    server.shutdownNow()
  }
}

private class QuietSystem(override val stove: Stove, private val prepareFailure: Boolean, private val metadataFailure: Boolean) : PluggedSystem, Reports, BeforeRunAware {
  var metadataReads = 0
  override val reportSystemName: String get() {
    metadataReads++
    if (metadataFailure) error("metadata failed")
    return "Quiet"
  }
  override fun snapshot() = SystemSnapshot("Quiet", emptyMap<String, Any>(), "")
  override suspend fun beforeRun() {
    if (prepareFailure) error("prepare failed")
  }
  override fun close() = Unit
}

private class FailingApplication : ApplicationUnderTest<Unit> {
  override suspend fun start(configurations: List<String>) {
    error("application failed")
  }
  override suspend fun stop() = Unit
}
