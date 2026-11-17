package com.trendyol.stove.system

import arrow.core.*
import com.trendyol.stove.events.*
import com.trendyol.stove.system.abstractions.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

internal class StoveLifecycle(
  private val stove: Stove,
  private val events: StoveEventBus,
  private val environment: RunEnvironment
) {
  var application: Option<ApplicationUnderTest<*>> = None
  var context: Option<Any> = None
  private val cleanup = mutableListOf<AutoCloseable>()
  private val closed = AtomicBoolean(false)
  private var applicationStartAttempted = false
  private var started = false
  private var startupFailure: Option<Throwable> = None

  fun <T : AutoCloseable> register(resource: T): T {
    check(!closed.get()) { "Cannot register resources on a closed Stove" }
    if (cleanup.none { it === resource }) cleanup.add(resource)
    return resource
  }

  suspend fun run() {
    check(!closed.get()) { "Stove has been closed" }
    check(application.isSome()) { "Register an application runner before starting Stove" }
    check(!started) { "Stove has already been started" }
    started = true
    var announced = false
    try {
      events.publish(RunStarting(stove.runComponents()))
      announced = true
      events.connect(stove)
      startSystems()
      events.publish(RunReady)
    } catch (failure: Throwable) {
      if (!announced) events.publish(RunStarting(environment.snapshot()))
      startupFailure = Some(failure)
      rollback(failure)
      throw failure
    }
  }

  private suspend fun startSystems() {
    val application = application.getOrElse { error("Application runner is not registered") }
    val systems = stove.allRegisteredSystems().distinctInstances()
    phase<BeforeRunAware>(systems, PhaseName.PREPARE) { it.beforeRun() }
    phase<RunAware>(systems, PhaseName.START) { it.run() }
    environment.phase(application, PhaseName.START) {
      val configurations = systems.filterIsInstance<ExposesConfiguration>().flatMap { it.configuration() }
      applicationStartAttempted = true
      context = Some(application.start(configurations))
    }
    phase<AfterRunAware>(systems, PhaseName.AFTER_START) { it.afterRun() }
  }

  private suspend inline fun <reified T : Any> phase(
    systems: Collection<PluggedSystem>,
    name: PhaseName,
    crossinline action: LifecycleAction<T>
  ): Unit = coroutineScope {
    systems.filterIsInstance<T>().map { system ->
      async(Dispatchers.IO) { environment.phase(system, name) { action(system) } }
    }.awaitAll()
  }

  private suspend fun rollback(failure: Throwable): Unit = withContext(NonCancellable) {
    try {
      shutdown()
    } catch (cleanupFailure: Throwable) {
      if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
    }
  }

  suspend fun shutdown() {
    if (!closed.compareAndSet(false, true)) return
    val observers = cleanup.filterIsInstance<RunObserver>().asReversed()
    var cleanupFailure: Option<Throwable> = None
    closeAll(
      {
        try {
          closeAll(shutdownActions()) { it() }
        } catch (failure: Throwable) {
          cleanupFailure = Some(failure)
          throw failure
        }
      },
      {
        val failure = startupFailure.fold({ cleanupFailure }, { Some(it) })
        if (started) events.publish(RunFinished(failure.map { it.description() }))
      },
      { events.disconnect() },
      { closeAll(observers) { it.close() } },
      { events.close() }
    )
  }

  private fun shutdownActions(): List<ShutdownAction> = buildList {
    add { events.capturePendingSnapshots() }
    val resources = cleanup.filterIsInstance<ApplicationResource>()
    resources.forEach { resource -> add { environment.phase(resource, PhaseName.BEFORE_STOP) { resource.beforeApplicationStop() } } }
    if (applicationStartAttempted) {
      applicationStartAttempted = false
      add { application.onSome { runner -> environment.phase(runner, PhaseName.STOP) { runner.stop() } } }
    }
    resources.asReversed().forEach { resource -> add { closeResource(resource) } }
    cleanup.asReversed().filterNot { it is ApplicationResource || it is RunObserver }
      .forEach { resource -> add { closeResource(resource) } }
    cleanup.clear()
    add { stove.dumpFailureReport() }
  }

  private suspend fun closeResource(resource: AutoCloseable) {
    environment.phase(resource, PhaseName.CLOSE) { resource.close() }
  }
}

private typealias LifecycleAction<T> = suspend (T) -> Unit
private typealias ShutdownAction = suspend () -> Unit
