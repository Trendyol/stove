package com.trendyol.stove.system

import arrow.core.*
import com.trendyol.stove.events.*
import com.trendyol.stove.system.abstractions.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class StoveLifecycle(
  private val stove: Stove,
  private val events: StoveEventBus,
  private val environment: RunEnvironment
) {
  var application: Option<ApplicationUnderTest<*>> = None
  var context: Option<Any> = None
  private val lifecycleLock = ReentrantLock()
  private val transition = Mutex()
  private var cleanup: Option<List<AutoCloseable>> = Some(emptyList())
  private enum class Startup { NEW, SYSTEMS, APPLICATION }
  private var startup = Startup.NEW

  fun <T : AutoCloseable> register(resource: T): T = lifecycleLock.withLock {
    val resources = cleanup.getOrElse { error("Cannot register resources on a closed Stove") }
    if (resources.none { it === resource }) cleanup = Some(resources + resource)
    resource
  }

  suspend fun run(): Unit = transition.withLock {
    lifecycleLock.withLock {
      check(cleanup.isSome()) { "Stove has been closed" }
      check(application.isSome()) { "Register an application runner before starting Stove" }
      check(startup == Startup.NEW) { "Stove has already been started" }
      startup = Startup.SYSTEMS
      Stove.instance = stove
    }
    try {
      val components = runCatching { stove.readRunComponents(freeze = true) }
        .onFailure { events.publish(RunStarting(environment.snapshot())) }.getOrThrow()
      events.publish(RunStarting(components))
      events.connect(stove)
      startSystems()
      events.publish(RunReady)
    } catch (failure: Throwable) {
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
      startup = Startup.APPLICATION
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
      dispose(Some(failure))
    } catch (cleanupFailure: Throwable) {
      if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
    }
  }

  suspend fun shutdown() {
    if (lifecycleLock.withLock { cleanup.isNone() }) return
    transition.withLock { dispose(None) }
  }

  private suspend fun dispose(startupFailure: Option<Throwable>) {
    val registered = lifecycleLock.withLock {
      cleanup.getOrElse { return }.also { cleanup = None }
    }
    val observers = registered.filterIsInstance<RunObserver>().asReversed()
    val cleanupResult = runCatching { closeAll(shutdownActions(registered)) { it() } }
    closeAll(
      { cleanupResult.getOrThrow() },
      {
        val failure = cleanupResult.fold({ startupFailure }, { cleanupError -> Some(startupFailure.getOrElse { cleanupError }) })
        if (startup != Startup.NEW) events.publish(RunFinished(failure.map { it.description() }))
      },
      { events.disconnect() },
      { closeAll(observers) { it.close() } },
      { events.close() }
    )
  }

  private fun shutdownActions(registered: List<AutoCloseable>): List<ShutdownAction> = buildList {
    add { events.capturePendingSnapshots() }
    val resources = registered.filterIsInstance<ApplicationResource>()
    resources.forEach { resource -> add { environment.phase(resource, PhaseName.BEFORE_STOP) { resource.beforeApplicationStop() } } }
    if (startup == Startup.APPLICATION) {
      add { application.onSome { runner -> environment.phase(runner, PhaseName.STOP) { runner.stop() } } }
    }
    resources.asReversed().filterNot { it is RunObserver }.forEach { resource -> add { closeResource(resource) } }
    registered.asReversed().filterNot { it is ApplicationResource || it is RunObserver }
      .forEach { resource -> add { closeResource(resource) } }
    add { stove.dumpFailureReport() }
  }

  private suspend fun closeResource(resource: AutoCloseable) {
    environment.phase(resource, PhaseName.CLOSE) { resource.close() }
  }
}

private typealias LifecycleAction<T> = suspend (T) -> Unit
private typealias ShutdownAction = suspend () -> Unit
