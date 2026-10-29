package com.trendyol.stove.chaos

import com.trendyol.stove.system.ValidationDsl
import com.trendyol.stove.system.abstractions.PluggedSystem
import com.trendyol.stove.system.annotations.StoveDsl
import kotlinx.coroutines.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Registration-local capabilities. Enabling chaos allocates no sockets until configuration is exposed. */
@StoveDsl
class ChaosSetupScope {
  var chaosEnabled: Boolean = false
    private set

  fun enableChaos() {
    chaosEnabled = true
  }
}

/** Implemented by modules with an application endpoint adapter. */
@StoveDsl
interface ChaosCapableSystem : PluggedSystem

/** Converts only the application-facing configuration; native clients retain the original configuration. */
interface ChaosEndpointAdapter<C> {
  fun endpoint(configuration: C): TcpEndpoint

  fun applicationConfiguration(direct: C, endpoint: TcpEndpoint): C
}

/** Module registration hook. Call before any route is started. */
fun ChaosCapableSystem.enableChaosRouting() {
  stove.chaosSystem().enable(this)
}

/** Module configuration hook; disabled systems return the original configuration unchanged. */
fun <C> ChaosCapableSystem.applicationConfiguration(direct: C, adapter: ChaosEndpointAdapter<C>): C =
  stove.existingChaosSystem()?.route(this, direct, adapter) ?: direct

/**
 * Module validation hook. Reservations survive healing until this entire block exits, including recovery assertions.
 * Shared-application chaos tests must run serially; route ownership does not isolate other application traffic.
 */
suspend fun <S : ChaosCapableSystem, T> withSystemValidation(system: S, block: suspend S.() -> T): T = coroutineScope {
  val frame = ChaosValidationScope(system, currentCoroutineContext().job)
  try {
    withContext(frame) { block(system) }
  } finally {
    system.stove.existingChaosSystem()?.release(frame)
  }
}

/**
 * Executes once with this system's application route faulted. Restores connectivity on success, failure or cancellation.
 * The block exposes the ordinary cross-system DSL. Recovery assertions follow in the enclosing system block.
 */
suspend fun <T> ChaosCapableSystem.duringFault(
  fault: NetworkFault = NetworkFault.Unavailable,
  block: @StoveDsl suspend ValidationDsl.() -> T
): T {
  val (frame, coordinator) = validationContext("duringFault")
  return coordinator.duringFault(frame, fault) { block(ValidationDsl(stove)) }
}

internal class ChaosValidationScope(val system: ChaosCapableSystem, val job: Job) :
  AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<ChaosValidationScope>
}

/**
 * Runs named baseline, fault and recovery phases once under one finite deadline. Reserves the route
 * before baseline; ownership remains until the enclosing validation block exits. Shared applications
 * still require a dedicated fixture or test-framework resource lock. Each phase waits for its own child coroutines;
 * externally scoped jobs remain the caller's responsibility. Only observation assertions should retry.
 */
suspend fun ChaosCapableSystem.chaosExperiment(
  name: String,
  fault: NetworkFault,
  timeout: kotlin.time.Duration,
  baseline: @StoveDsl suspend ValidationDsl.() -> Unit,
  faulted: @StoveDsl suspend ValidationDsl.() -> Unit,
  recovery: @StoveDsl suspend ValidationDsl.() -> Unit
) {
  val (frame, coordinator) = validationContext("chaosExperiment")
  coordinator.experiment(
    frame,
    name,
    fault,
    timeout,
    { baseline(ValidationDsl(stove)) },
    { faulted(ValidationDsl(stove)) },
    { recovery(ValidationDsl(stove)) }
  )
}

/**
 * Waits for bytes encountered by the current stall in one direction, including bytes already observed.
 * Does not prove business commitment. Fails if the fault ends first; never carries evidence across faults.
 */
suspend fun ChaosCapableSystem.awaitStalledTraffic(direction: TrafficDirection, timeout: kotlin.time.Duration) {
  val (frame, coordinator) = validationContext("awaitStalledTraffic")
  coordinator.awaitStalledTraffic(frame, direction, timeout)
}

private suspend fun ChaosCapableSystem.validationContext(operation: String): Pair<ChaosValidationScope, ChaosSystem> {
  val frame = currentCoroutineContext()[ChaosValidationScope]
  check(frame != null && frame.system === this) { "$operation must run inside this system's validation block" }
  val coordinator = checkNotNull(stove.existingChaosSystem()) {
    "Chaos is not enabled for this system; call enableChaos() in its setup block"
  }
  return frame to coordinator
}
