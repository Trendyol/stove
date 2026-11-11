package com.trendyol.stove.chaos

import arrow.core.getOrElse
import com.trendyol.stove.reporting.Reports
import com.trendyol.stove.reporting.SystemSnapshot
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.ApplicationResource
import com.trendyol.stove.system.closeAll
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

internal fun Stove.existingChaosSystem(): ChaosSystem? = getOrNone<ChaosSystem>().getOrNull()

internal fun Stove.chaosSystem(): ChaosSystem = getOrNone<ChaosSystem>().getOrElse { getOrRegister(ChaosSystem(this)) }

internal interface FaultController : AutoCloseable {
  fun start(target: TcpEndpoint): TcpEndpoint
  fun apply(fault: NetworkFault)
  fun heal()
  fun captureStalledTraffic(direction: TrafficDirection): StalledTrafficObservation =
    error("This fault controller does not support traffic observations")
}

private class TcpFaultController(name: String) : FaultController {
  private val proxy = TcpChaosProxy(name)

  override fun start(target: TcpEndpoint): TcpEndpoint {
    proxy.start(target)
    return proxy.endpoint
  }

  override fun apply(fault: NetworkFault) = proxy.applyFault(fault)
  override fun heal() = proxy.heal()
  override fun captureStalledTraffic(direction: TrafficDirection) = proxy.captureStalledTraffic(direction)
  override fun close() = proxy.close()
}

internal class ChaosSystem(
  override val stove: Stove,
  private val controllers: (String) -> FaultController = ::TcpFaultController
) : ApplicationResource, Reports {
  private val routes = mutableListOf<Route>()
  private val lock = ReentrantLock()
  private var stopping = false

  fun enable(system: ChaosCapableSystem): Unit = lock.withLock {
    check(!stopping) { "Chaos is shutting down" }
    if (findRoute(system) != null) return
    val name = (system as? Reports)?.reportSystemName ?: system.javaClass.simpleName
    routes += Route(system, name, controllers(name))
  }

  fun <C> route(system: ChaosCapableSystem, direct: C, adapter: ChaosEndpointAdapter<C>): C = lock.withLock {
    val route = findRoute(system) ?: return direct
    route.checkAvailable(stopping)
    adapter.reroute(direct, route::start)
  }

  suspend fun <T> duringFault(
    scope: ChaosValidationScope,
    system: ChaosCapableSystem,
    fault: NetworkFault,
    block: suspend () -> T
  ): T = duringFault(scope, system, fault, experiment = null, block)

  private suspend fun <T> duringFault(
    scope: ChaosValidationScope,
    system: ChaosCapableSystem,
    fault: NetworkFault,
    experiment: ExperimentToken?,
    block: suspend () -> T
  ): T {
    val route = reserve(scope, system, experiment)
    val metadata = mapOf("target" to route.name, "fault" to fault.toString())
    return restoring(restore = {
      report(action = "Restore connectivity", metadata = metadata) { heal(route) }
    }) {
      report(action = "Apply fault", metadata = metadata) { applyFault(route, fault) }
      completePhase(block)
    }
  }

  suspend fun experiment(
    scope: ChaosValidationScope,
    system: ChaosCapableSystem,
    name: String,
    fault: NetworkFault,
    timeout: Duration,
    baseline: suspend () -> Unit,
    faulted: suspend () -> Unit,
    recovery: suspend () -> Unit
  ) {
    require(name.isNotBlank()) { "Experiment name cannot be blank" }
    require(timeout.isFinite() && timeout.isPositive()) { "Experiment timeout must be positive and finite" }
    val (route, experiment) = lock.withLock {
      val route = requireRoute(system)
      route.checkAvailable(stopping)
      route to route.beginExperiment(scope)
    }
    try {
      withTimeout(timeout) {
        val metadata = mapOf("experiment" to name, "target" to route.name)
        report(action = "Baseline", metadata = metadata) { completePhase(baseline) }
        report(action = "Fault phase", metadata = metadata) { duringFault(scope, system, fault, experiment, faulted) }
        report(action = "Recovery", metadata = metadata) { completePhase(recovery) }
      }
    } finally {
      lock.withLock { route.endExperiment(experiment) }
    }
  }

  suspend fun awaitStalledTraffic(
    scope: ChaosValidationScope,
    system: ChaosCapableSystem,
    direction: TrafficDirection?,
    timeout: Duration
  ) {
    val (target, observed, observation) = lock.withLock {
      val route = requireRoute(system)
      route.checkAvailable(stopping)
      val observed = direction ?: route.stalledDirection()
      Triple(route.name, observed, route.captureStalledTraffic(scope, observed))
    }
    report(action = "Observe stalled traffic", metadata = mapOf("target" to target, "direction" to observed.name)) {
      observation.await(timeout)
    }
  }

  fun release(scope: ChaosValidationScope) = lock.withLock {
    routes.forEach { it.release(scope) }
  }

  private fun reserve(scope: ChaosValidationScope, system: ChaosCapableSystem, experiment: ExperimentToken?): Route = lock.withLock {
    val route = requireRoute(system)
    route.checkAvailable(stopping)
    route.reserve(scope, experiment)
    route
  }

  private fun applyFault(route: Route, fault: NetworkFault) = lock.withLock {
    check(!stopping) { "Chaos is shutting down" }
    route.apply(fault)
  }

  private fun requireRoute(system: ChaosCapableSystem): Route = checkNotNull(findRoute(system)) {
    "Chaos is not enabled for this system; call enableChaos() in its setup block"
  }

  // Route state is always accessed under lock. Identity keeps equal keyed systems independent.
  private fun findRoute(system: ChaosCapableSystem): Route? = routes.firstOrNull { it.system === system }

  override suspend fun beforeApplicationStop() {
    val jobs = stopAdmission()
    jobs.forEach { it.cancel(CancellationException("Stove is shutting down")) }
    restoring(restore = ::healActiveRoutes) {
      closeAll(
        // Cancellation alone cannot unblock synchronous I/O waiting on a stalled route.
        ::healActiveRoutes,
        { withTimeout(5_000) { jobs.joinAll() } }
      )
    }
  }

  private fun stopAdmission(): List<Job> = lock.withLock {
    stopping = true
    routes.mapNotNull { it.ownerJob }.distinct()
  }

  private suspend fun healActiveRoutes() {
    val activeRoutes = lock.withLock { routes.filter { it.active }.asReversed() }
    closeAll(activeRoutes, ::heal)
  }

  private fun heal(route: Route) = lock.withLock { route.heal() }

  override fun close() {
    val owned = detachRoutes()
    runBlocking { closeAll(owned) { it.close() } }
  }

  private fun detachRoutes(): List<Route> = lock.withLock {
    stopping = true
    val owned = routes.asReversed().toList()
    routes.clear()
    owned
  }

  override fun snapshot(): SystemSnapshot = lock.withLock {
    SystemSnapshot(
      system = reportSystemName,
      state = mapOf("routes" to routes.map { it.snapshot() }),
      summary = "${routes.size} managed application route(s)"
    )
  }

  private class ExperimentToken

  private class Route(
    val system: ChaosCapableSystem,
    val name: String,
    private val controller: FaultController
  ) : AutoCloseable {
    private var experiment: ExperimentToken? = null
    private var started = false
    private var unknown = false
    private var owner: ChaosValidationScope? = null
    private var fault: NetworkFault? = null
    var active = false
      private set
    val ownerJob: Job? get() = owner?.job

    fun checkAvailable(stopping: Boolean) {
      check(!stopping && !unknown) { "Chaos route $name is unavailable; recreate the fixture" }
    }

    fun start(target: TcpEndpoint): TcpEndpoint {
      val endpoint = controller.start(target)
      started = true
      return endpoint
    }

    fun beginExperiment(scope: ChaosValidationScope): ExperimentToken {
      check(experiment == null) { "Nested experiments on $name are not supported" }
      checkOwner(scope)
      owner = scope
      return ExperimentToken().also { experiment = it }
    }

    fun endExperiment(token: ExperimentToken) {
      check(experiment === token) { "Experiment ownership changed on $name" }
      experiment = null
    }

    fun captureStalledTraffic(scope: ChaosValidationScope, direction: TrafficDirection): StalledTrafficObservation {
      check(owner === scope && active) { "Traffic observation requires an active fault owned by this validation block" }
      return controller.captureStalledTraffic(direction)
    }

    fun reserve(scope: ChaosValidationScope, token: ExperimentToken?) {
      checkOwner(scope)
      check(experiment === token) { "Standalone faults on $name are not supported while an experiment is running" }
      owner = scope
      // Reserve restoration before activation: a controller can mutate and then throw.
      active = true
    }

    private fun checkOwner(scope: ChaosValidationScope) {
      check(started) { "Chaos route $name has not been started" }
      check(owner == null || owner === scope) { "Chaos route $name is reserved by another validation block" }
      check(!active) { "Nested faults on $name are not supported" }
    }

    fun release(scope: ChaosValidationScope) {
      if (owner === scope) owner = null
    }

    fun apply(fault: NetworkFault) {
      this.fault = fault
      controller.apply(fault)
    }

    /** Request bytes reach a two-way stall first, so they are the default evidence. */
    fun stalledDirection(): TrafficDirection {
      val stall = checkNotNull(fault as? NetworkFault.Stall) { "Traffic observation requires an active stall on $name" }
      return if (stall.direction == TrafficDirection.Both) TrafficDirection.Requests else stall.direction
    }

    fun heal() {
      if (!active) return
      try {
        controller.heal()
        active = false
        fault = null
      } catch (failure: Throwable) {
        unknown = true
        throw failure
      }
    }

    fun snapshot(): Map<String, Any> = mapOf(
      "target" to name,
      "started" to started,
      "faultActive" to active,
      "reserved" to (owner != null),
      "restorationUnknown" to unknown
    )

    override fun close() = controller.close()
  }
}

/** Waits for phase children and retains the original completion failure before coroutine stack-trace recovery. */
private suspend fun <T> completePhase(block: suspend () -> T): T {
  val originalFailure = AtomicReference<Throwable?>()
  try {
    return coroutineScope {
      currentCoroutineContext().job.invokeOnCompletion { originalFailure.set(it) }
      block()
    }
  } catch (failure: Throwable) {
    // A later coroutine boundary can discard suppressed cleanup errors attached only to a recovered copy.
    throw originalFailure.get() ?: failure
  }
}
