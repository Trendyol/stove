package com.trendyol.stove.chaos

import arrow.core.getOrElse
import com.trendyol.stove.reporting.Reports
import com.trendyol.stove.reporting.SystemSnapshot
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.ApplicationResource
import com.trendyol.stove.system.closeAll
import kotlinx.coroutines.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal fun Stove.existingChaosSystem(): ChaosSystem? = getOrNone<ChaosSystem>().getOrNull()

internal fun Stove.chaosSystem(): ChaosSystem = getOrNone<ChaosSystem>().getOrElse { getOrRegister(ChaosSystem(this)) }

internal interface FaultController : AutoCloseable {
  fun start(target: TcpEndpoint): TcpEndpoint
  fun apply(fault: NetworkFault)
  fun heal()
}

private class TcpFaultController(name: String) : FaultController {
  private val proxy = TcpChaosProxy(name)

  override fun start(target: TcpEndpoint): TcpEndpoint {
    proxy.start(target)
    return proxy.endpoint
  }

  override fun apply(fault: NetworkFault) = proxy.applyFault(fault)
  override fun heal() = proxy.heal()
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
    adapter.applicationConfiguration(direct, route.start(adapter.endpoint(direct)))
  }

  suspend fun <T> duringFault(scope: ChaosValidationScope, fault: NetworkFault, block: suspend () -> T): T {
    val route = reserve(scope)
    val metadata = mapOf("target" to route.name, "fault" to fault.toString())
    return restoring(restore = {
      report(action = "Restore connectivity", metadata = metadata) { heal(route) }
    }) {
      report(action = "Apply fault", metadata = metadata) { applyFault(route, fault) }
      block()
    }
  }

  fun release(scope: ChaosValidationScope) = lock.withLock {
    routes.forEach { it.release(scope) }
  }

  private fun reserve(scope: ChaosValidationScope): Route = lock.withLock {
    val route = findRoute(scope.system)
    check(route != null) { "Chaos is not enabled for this system; call enableChaos() in its setup block" }
    route.checkAvailable(stopping)
    route.reserve(scope)
    route
  }

  private fun applyFault(route: Route, fault: NetworkFault) = lock.withLock {
    check(!stopping) { "Chaos is shutting down" }
    route.apply(fault)
  }

  // Route state is always accessed under lock. Identity keeps equal keyed systems independent.
  private fun findRoute(system: ChaosCapableSystem): Route? = routes.firstOrNull { it.system === system }

  override suspend fun beforeApplicationStop() {
    val jobs = stopAdmission()
    jobs.forEach { it.cancel(CancellationException("Stove is shutting down")) }
    restoring(restore = ::healActiveRoutes) {
      withTimeout(5_000) { jobs.joinAll() }
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

  private class Route(
    val system: ChaosCapableSystem,
    val name: String,
    private val controller: FaultController
  ) : AutoCloseable {
    private var started = false
    private var unknown = false
    private var owner: ChaosValidationScope? = null
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

    fun reserve(scope: ChaosValidationScope) {
      check(started) { "Chaos route $name has not been started" }
      check(owner == null || owner === scope) { "Chaos route $name is reserved by another validation block" }
      check(!active) { "Nested faults on $name are not supported" }
      owner = scope
      // Reserve restoration before activation: a controller can mutate and then throw.
      active = true
    }

    fun release(scope: ChaosValidationScope) {
      if (owner === scope) owner = null
    }

    fun apply(fault: NetworkFault) = controller.apply(fault)

    fun heal() {
      try {
        controller.heal()
        active = false
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
