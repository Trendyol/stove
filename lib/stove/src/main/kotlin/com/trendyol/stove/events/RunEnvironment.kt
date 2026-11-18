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

  fun components(
    systems: Collection<PluggedSystem>,
    keyedSystems: List<Pair<SystemKey, PluggedSystem>>,
    application: Option<ApplicationUnderTest<*>>,
    freeze: Boolean
  ): List<RunComponent> = lock.withLock {
    if (registrations.isNotEmpty()) return snapshot()
    val current = buildList {
      systems.forEach { add(registration(it, size)) }
      keyedSystems.forEach { (key, system) -> add(registration(system, size, Some(key))) }
      application.onSome { add(registration(it, size, role = ComponentRole.APPLICATION)) }
    }
    val components = current.map { it.component }
    if (freeze) {
      registrations = current
      state.value = components
    }
    current.firstNotNullOfOrNull { it.metadataFailure.getOrNull() }?.let { throw it }
    components
  }

  fun snapshot(): List<RunComponent> = state.value

  suspend fun <T> phase(target: Any, name: PhaseName, role: ComponentRole = ComponentRole.SYSTEM, block: PhaseOperation<T>): T {
    val started = Instant.now()
    val elapsed = TimeSource.Monotonic.markNow()
    update(target, role, ComponentPhase(name, PhaseStatus.RUNNING, started))
    try {
      return block().also {
        update(target, role, ComponentPhase(name, PhaseStatus.SUCCEEDED, started, elapsed.elapsedNow().inWholeMilliseconds))
      }
    } catch (failure: Throwable) {
      val status = if (failure is CancellationException) PhaseStatus.CANCELLED else PhaseStatus.FAILED
      update(
        target,
        role,
        ComponentPhase(name, status, started, elapsed.elapsedNow().inWholeMilliseconds, Some(failure.description())),
        Some(failure)
      )
      throw failure
    }
  }

  private fun update(target: Any, role: ComponentRole, phase: ComponentPhase, failure: Option<Throwable> = None): Unit = lock.withLock {
    if (registrations.none { it.matches(target, role) }) return
    registrations = registrations.map { registration ->
      if (!registration.matches(target, role)) return@map registration
      val component = registration.component
      registration.copy(component = component.copy(phases = component.phases.filterNot { it.name == phase.name } + phase))
    }
    val snapshot = registrations.map { it.component }
    state.value = snapshot
    publish(EnvironmentChanged(snapshot))
    registrations.filter { it.matches(target, role) }.forEach { it.event(phase, failure).onSome(publish) }
  }
}

private data class Registration(
  val target: Any,
  val key: Option<SystemKey>,
  val component: RunComponent,
  val metadataFailure: Option<Throwable>
) {
  fun matches(instance: Any, role: ComponentRole) = target === instance && component.role == role

  fun event(phase: ComponentPhase, failure: Option<Throwable>): Option<StoveEvent> {
    if (component.role != ComponentRole.SYSTEM) return None
    val duration = phase.durationMs.milliseconds
    return when {
      phase.status == PhaseStatus.FAILED -> failure.map { SystemFailed(component, phase.name, it, target.javaClass, key) }
      phase.status != PhaseStatus.SUCCEEDED -> None
      phase.name == PhaseName.START -> Some(SystemStarted(component, duration, target.javaClass, key))
      phase.name == PhaseName.CLOSE -> Some(SystemStopped(component, duration, target.javaClass, key))
      else -> None
    }
  }
}

private fun registration(target: Any, index: Int, key: Option<SystemKey> = None, role: ComponentRole = ComponentRole.SYSTEM): Registration {
  val type = target.javaClass
  val reported = runCatching { (target as? Reports)?.reportSystemName.orEmpty() }
  val name = reported.getOrDefault("").ifBlank { type.simpleName.ifBlank { type.name } }
  val component = RunComponent("component-${index + 1}", name, type.name, key.map(::keyDisplayName), role)
  return Registration(target, key, component, reported.exceptionOrNull().toOption())
}

private typealias PhaseOperation<T> = suspend () -> T
