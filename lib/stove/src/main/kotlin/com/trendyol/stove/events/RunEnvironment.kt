package com.trendyol.stove.events

import arrow.core.*
import com.trendyol.stove.reporting.Reports
import com.trendyol.stove.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.system.abstractions.PluggedSystem
import com.trendyol.stove.system.abstractions.SystemKey
import com.trendyol.stove.system.abstractions.keyDisplayName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal class RunEnvironment(private val publish: StoveEventHandler) {
  private val lock = ReentrantLock()
  private val state = MutableStateFlow(emptyList<RunComponent>())
  val stream: StateFlow<List<RunComponent>> = state.asStateFlow()
  private var registrations = emptyList<Registration>()
  private var captured = false

  fun capture(
    systems: Collection<PluggedSystem>,
    keyedSystems: List<Pair<SystemKey, PluggedSystem>>,
    application: Option<ApplicationUnderTest<*>>
  ): List<RunComponent> = lock.withLock {
    if (captured) return snapshot()
    systems.forEach { register(it) }
    keyedSystems.forEach { (key, system) -> register(system, Some(key)) }
    application.onSome { register(it, application = true) }
    captured = true
    snapshot().also { state.value = it }
  }

  fun register(target: Any, key: Option<SystemKey> = None, application: Boolean = false): Unit = lock.withLock {
    val id = "component-${registrations.size + 1}"
    val component = RunComponent(
      id = id,
      name = if (target is Reports) target.reportSystemName else target.javaClass.simpleName.ifBlank { target.javaClass.name },
      type = target.javaClass.name,
      key = key.map(::keyDisplayName),
      role = if (application) ComponentRole.APPLICATION else ComponentRole.SYSTEM
    )
    registrations += Registration(target, key, component)
  }

  fun snapshot(): List<RunComponent> = lock.withLock { registrations.map { it.component } }

  suspend fun <T> phase(target: Any, name: PhaseName, block: PhaseOperation<T>): T {
    val started = Instant.now()
    val elapsed = TimeSource.Monotonic.markNow()
    update(target, ComponentPhase(name, PhaseStatus.RUNNING, started))
    try {
      return block().also {
        update(target, ComponentPhase(name, PhaseStatus.SUCCEEDED, started, elapsed.elapsedNow().inWholeMilliseconds))
      }
    } catch (failure: Throwable) {
      val status = if (failure is CancellationException) PhaseStatus.CANCELLED else PhaseStatus.FAILED
      update(
        target,
        ComponentPhase(name, status, started, elapsed.elapsedNow().inWholeMilliseconds, Some(failure.description())),
        Some(failure)
      )
      throw failure
    }
  }

  private fun update(target: Any, phase: ComponentPhase, failure: Option<Throwable> = None): Unit = lock.withLock {
    if (registrations.none { it.target === target }) return
    registrations = registrations.map { registration ->
      if (registration.target !== target) return@map registration
      val component = registration.component
      registration.copy(component = component.copy(phases = component.phases.filterNot { it.name == phase.name } + phase))
    }
    val snapshot = snapshot()
    state.value = snapshot
    publish(EnvironmentChanged(snapshot))
    registrations.filter { it.target === target && it.component.role == ComponentRole.SYSTEM }.forEach {
      when {
        phase.name == PhaseName.START && phase.status == PhaseStatus.SUCCEEDED ->
          publish(SystemStarted(it.component, phase.durationMs.milliseconds, target.javaClass, it.key))

        phase.name == PhaseName.CLOSE && phase.status == PhaseStatus.SUCCEEDED ->
          publish(SystemStopped(it.component, phase.durationMs.milliseconds, target.javaClass, it.key))

        phase.status == PhaseStatus.FAILED -> failure.onSome { error ->
          publish(SystemFailed(it.component, phase.name, error, target.javaClass, it.key))
        }
      }
    }
  }
}

private data class Registration(val target: Any, val key: Option<SystemKey>, val component: RunComponent)

private typealias PhaseOperation<T> = suspend () -> T
