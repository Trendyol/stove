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
import java.util.IdentityHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.TimeSource

internal class RunEnvironment(private val publish: StoveEventHandler) {
  private val lock = ReentrantLock()
  private val state = MutableStateFlow(emptyList<RunComponent>())
  val stream: StateFlow<List<RunComponent>> = state.asStateFlow()
  private val identities = IdentityHashMap<Any, MutableList<String>>()
  private val components = linkedMapOf<String, RunComponent>()

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
    val id = "component-${components.size + 1}"
    components[id] = RunComponent(
      id = id,
      name = (target as? Reports)?.reportSystemName ?: target.javaClass.simpleName.ifBlank { target.javaClass.name },
      type = target.javaClass.name,
      key = key.map(::keyDisplayName),
      role = if (application) ComponentRole.APPLICATION else ComponentRole.SYSTEM
    )
    identities.getOrPut(target) { mutableListOf() }.add(id)
  }

  fun snapshot(): List<RunComponent> = lock.withLock { components.values.toList() }

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
      update(target, ComponentPhase(name, status, started, elapsed.elapsedNow().inWholeMilliseconds, Some(failure.description())))
      throw failure
    }
  }

  private fun update(target: Any, phase: ComponentPhase): Unit = lock.withLock {
    val ids = identities.getOrNone(target).getOrElse { return }
    ids.forEach { id ->
      val component = components.getValue(id)
      components[id] = component.copy(phases = component.phases.filterNot { it.name == phase.name } + phase)
    }
    val snapshot = snapshot()
    state.value = snapshot
    publish(EnvironmentChanged(snapshot))
  }
}

private typealias PhaseOperation<T> = suspend () -> T
