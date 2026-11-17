package com.trendyol.stove.events

import arrow.core.*
import com.trendyol.stove.reporting.Reports
import com.trendyol.stove.reporting.SystemSnapshot
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class RunEnvironmentTest : FunSpec({
  test("inventories non-reporting systems, keyed registrations and the AUT before startup") {
    EnvironmentFixture().use { fixture ->
      fixture.stove.run()
      fixture.recorder.drain()
      val starting = fixture.events.first() as RunStarting
      starting.components.size shouldBe 4
      starting.components.map { it.key } shouldBe listOf(None, None, Some("Analytics"), None)
      starting.components.last().role shouldBe ComponentRole.APPLICATION
      starting.components.all { it.phases.isEmpty() } shouldBe true
      fixture.events.any { it is RunReady } shouldBe true
      fixture.stove.close()
      fixture.observer.closes shouldBe 1
      fixture.observer.closedAfterFinish shouldBe true
      fixture.environment().single { it.role == ComponentRole.APPLICATION }.phases.map { it.name } shouldBe listOf(PhaseName.START, PhaseName.STOP)
    }
  }

  test("keeps every key when registrations share the same instance") {
    StoveEventBus().use { events ->
      val environment = RunEnvironment(events::publish)
      val component = Any()
      environment.register(component)
      environment.register(component, Some(Analytics))
      environment.phase(component, PhaseName.START) { Unit }
      environment.snapshot().map { it.key } shouldBe listOf(None, Some("Analytics"))
      environment.snapshot().map { it.id }.toSet().size shouldBe 2
      environment.snapshot().all { it.phases.single().status == PhaseStatus.SUCCEEDED } shouldBe true
    }
  }

  test("failed startup still reports unstarted components and rollback before the final event") {
    EnvironmentFixture().use { fixture ->
      fixture.dependency.failure = Some(IllegalStateException())
      shouldThrow<IllegalStateException> { fixture.stove.run() }
      val application = fixture.environment().single { it.role == ComponentRole.APPLICATION }
      application.phases shouldBe emptyList()
      fixture.events.filterIsInstance<RunFinished>().single().failure shouldBe Some("java.lang.IllegalStateException")
      fixture.observer.closedAfterFinish shouldBe true
      fixture.environment().first().phases.map { it.status } shouldBe listOf(PhaseStatus.FAILED, PhaseStatus.SUCCEEDED)
      fixture.environment().first().phases.first().error shouldBe Some("java.lang.IllegalStateException")
    }
  }

  test("configured observers receive startup once per registration and match the actual key") {
    val starts = mutableListOf<SystemStarted>()
    val keyed = mutableListOf<SystemStarted>()
    val terminals = mutableListOf<RunFinished>()
    val stops = mutableListOf<SystemStopped>()
    val keyedStops = mutableListOf<SystemStopped>()
    val selected = DatabaseKey("selected")
    val other = DatabaseKey("other")
    Stove {
      events {
        observe<SystemStarted> { error("broken observer") }
        observe<SystemStarted> { starts += it }
        onSystemStarted<SharedLifecycle>(selected) { keyed += it }
        onSystemStopped<SharedLifecycle> { stops += it }
        onSystemStopped<SharedLifecycle>(selected) { keyedStops += it }
        observe<RunFinished> { terminals += it }
      }
    }.use { stove ->
      val shared = stove.getOrRegister(selected, SharedLifecycle(stove))
      stove.getOrRegister(other, shared)
      stove.getOrRegister(EnvironmentDependency(stove))
      stove.applicationUnderTest(EnvironmentApplication()).run()
    }
    starts.map { it.key } shouldBe listOf(Some(selected), Some(other))
    keyed.single() shouldBe starts.first()
    starts.all { it.source.phases.last().status == PhaseStatus.SUCCEEDED } shouldBe true
    stops.map { it.key } shouldBe listOf(Some(selected), Some(other))
    keyedStops.single() shouldBe stops.first()
    terminals.size shouldBe 1
  }

  test("a failed system start emits the terminal event without a successful start") {
    val observed = mutableListOf<StoveEvent>()
    val failures = mutableListOf<SystemFailed>()
    val selected = DatabaseKey("failed")
    Stove {
      events {
        observe<StoveEvent> { observed += it }
        onSystemFailed<FailingLifecycle>(selected) { failures += it }
      }
    }.use { stove ->
      stove.getOrRegister(selected, FailingLifecycle(stove))
      stove.applicationUnderTest(EnvironmentApplication())
      shouldThrow<IllegalStateException> { stove.run() }
      observed.filterIsInstance<SystemStarted>().size shouldBe 0
      failures.single().phase shouldBe PhaseName.START
      failures.single().error.message shouldBe "startup failed"
      observed.filterIsInstance<SystemStopped>().single().key shouldBe Some(selected)
      (observed.last() as RunFinished).failure shouldBe Some("startup failed")
    }
  }

  test("failed close emits a failure but not a stopped event") {
    val observed = mutableListOf<StoveEvent>()
    val stove = Stove { events { observe<StoveEvent> { observed += it } } }
    stove.getOrRegister(EnvironmentDependency(stove)).closeFailure = Some(IllegalStateException("close failed"))
    stove.applicationUnderTest(EnvironmentApplication()).run()
    stove.close()
    observed.filterIsInstance<SystemFailed>().single().let {
      it.phase shouldBe PhaseName.CLOSE
      it.error.message shouldBe "close failed"
    }
    observed.filterIsInstance<SystemStopped>().size shouldBe 0
  }

  test("one instance registered twice runs and closes only once") {
    Stove().use { stove ->
      val shared = stove.getOrRegister(SharedLifecycle(stove))
      stove.getOrRegister(Analytics, shared)
      stove.applicationUnderTest(EnvironmentApplication()).run()
      stove.close()
      shared.phases shouldBe listOf("prepare", "start", "after", "close")
      stove.events.environment.value.count { it.type == SharedLifecycle::class.java.name } shouldBe 2
    }
  }

  test("metadata failure still closes registered resources") {
    Stove().use { stove ->
      val broken = stove.getOrRegister(BrokenMetadata(stove))
      stove.applicationUnderTest(EnvironmentApplication())
      shouldThrow<IllegalStateException> { stove.run() }.message shouldBe "metadata failed"
      broken.closed shouldBe true
    }
  }

  test("cancellation is recorded and cleanup does not replace the startup failure") {
    EnvironmentFixture().use { fixture ->
      val failure = CancellationException("cancelled")
      fixture.dependency.failure = Some(failure)
      fixture.dependency.closeFailure = Some(IllegalStateException("cleanup"))
      fixture.observer.failure = Some(IllegalArgumentException("exporter"))
      shouldThrow<CancellationException> { fixture.stove.run() }.message shouldBe "cancelled"
      fixture.environment().first().phases.map { it.status } shouldBe listOf(PhaseStatus.CANCELLED, PhaseStatus.FAILED)
      fixture.events.filterIsInstance<RunFinished>().single().failure shouldBe Some("cancelled")
      fixture.observer.closedAfterFinish shouldBe true
    }
  }
})

private data class DatabaseKey(val name: String) : SystemKey

private class FailingLifecycle(override val stove: Stove) : PluggedSystem, RunAware {
  override suspend fun run(): Unit = error("startup failed")
  override suspend fun stop() = Unit
  override fun close() = Unit
}

private object Analytics : SystemKey

private class EnvironmentFixture : AutoCloseable {
  val stove = Stove()
  val recorder = EventRecorder(stove.events)
  val events get() = recorder.events
  val dependency = stove.getOrRegister(EnvironmentDependency(stove))
  val observer = stove.getOrRegister(EnvironmentObserver(stove, recorder))
  init {
    stove.getOrRegister(Analytics, EnvironmentDependency(stove))
    stove.applicationUnderTest(EnvironmentApplication())
  }

  fun environment() = events.filterIsInstance<EnvironmentChanged>().last().components
  override fun close() {
    stove.close()
    recorder.close()
  }
}

private class EnvironmentDependency(override val stove: Stove) : PluggedSystem, BeforeRunAware {
  var failure: Option<Throwable> = None
  var closeFailure: Option<Throwable> = None
  override suspend fun beforeRun() {
    failure.onSome { throw it }
  }
  override fun close() {
    closeFailure.onSome { throw it }
  }
}

private class EnvironmentObserver(override val stove: Stove, private val recorder: EventRecorder) : RunObserver, ApplicationResource {
  var closes = 0
  override suspend fun beforeApplicationStop() = Unit
  var closedAfterFinish = false
  var failure: Option<Throwable> = None
  override fun close() {
    closes++
    runBlocking { recorder.drain() }
    closedAfterFinish = recorder.events.last() is RunFinished
    failure.onSome { throw it }
  }
}

private class EnvironmentApplication : ApplicationUnderTest<Unit> {
  override suspend fun start(configurations: List<String>) = Unit
  override suspend fun stop() = Unit
}

private class BrokenMetadata(override val stove: Stove) : PluggedSystem, Reports {
  var closed = false
  override val reportSystemName: String get() = error("metadata failed")
  override fun snapshot() = SystemSnapshot("broken", emptyMap<String, Any>(), "")
  override fun close() {
    closed = true
  }
}

private class SharedLifecycle(override val stove: Stove) : PluggedSystem, BeforeRunAware, RunAware, AfterRunAware {
  val phases = mutableListOf<String>()
  override suspend fun beforeRun() {
    phases += "prepare"
  }
  override suspend fun run() {
    phases += "start"
  }
  override suspend fun afterRun() {
    phases += "after"
  }
  override suspend fun stop() = Unit
  override fun close() {
    phases += "close"
  }
}
