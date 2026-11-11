package com.trendyol.stove.chaos

import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.ValidationDsl
import com.trendyol.stove.system.annotations.StoveDsl
import kotlinx.coroutines.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * Executes once with this system's application route faulted. Restores connectivity on success, failure or cancellation.
 * The block exposes the ordinary cross-system DSL and receives the faulted system. Inside `stove { }` the route stays
 * reserved until that block exits, so recovery assertions can follow the fault.
 * Shared-application chaos tests must run serially; route ownership does not isolate other application traffic.
 */
suspend fun <S : ChaosCapableSystem, T> S.duringFault(
  fault: NetworkFault = NetworkFault.Unavailable,
  block: @StoveDsl suspend ValidationDsl.(S) -> T
): T = owned { coordinator, frame ->
  coordinator.duringFault(frame, this, fault) { block(ValidationDsl(stove), this) }
}

/**
 * Runs named baseline, fault and recovery phases once under one finite deadline. Reserves the route
 * before baseline; inside `stove { }` ownership remains until that block exits. Shared applications
 * still require a dedicated fixture or test-framework resource lock. Each phase waits for its own child coroutines;
 * externally scoped jobs remain the caller's responsibility. Only observation assertions should retry.
 */
suspend fun <S : ChaosCapableSystem> S.chaosExperiment(
  name: String,
  fault: NetworkFault,
  timeout: Duration,
  baseline: @StoveDsl suspend ValidationDsl.(S) -> Unit,
  faulted: @StoveDsl suspend ValidationDsl.(S) -> Unit,
  recovery: @StoveDsl suspend ValidationDsl.(S) -> Unit
): Unit = owned { coordinator, frame ->
  coordinator.experiment(
    frame,
    this,
    name,
    fault,
    timeout,
    { baseline(ValidationDsl(stove), this) },
    { faulted(ValidationDsl(stove), this) },
    { recovery(ValidationDsl(stove), this) }
  )
}

/**
 * Waits for bytes encountered by the current stall in one direction, including bytes already observed.
 * [direction] defaults to the stalled direction (requests when both are stalled).
 * Does not prove business commitment. Fails if the fault ends first; never carries evidence across faults.
 */
suspend fun ChaosCapableSystem.awaitStalledTraffic(timeout: Duration, direction: TrafficDirection? = null): Unit =
  owned { coordinator, frame -> coordinator.awaitStalledTraffic(frame, this, direction, timeout) }

/**
 * Starts [block] in the background, waits until its traffic reaches the current stall and returns the pending result.
 * The request outlives the fault phase, so await it in recovery; its failure surfaces only from [Deferred.await].
 */
suspend fun <T> ChaosCapableSystem.sendIntoStall(
  timeout: Duration,
  direction: TrafficDirection? = null,
  block: @StoveDsl suspend ValidationDsl.() -> T
): Deferred<T> {
  val frame = checkNotNull(currentCoroutineContext()[ChaosValidationScope]) { "sendIntoStall must run inside a fault block" }
  // Parented to the validation frame, not the fault phase: the phase would otherwise wait for the stalled request before healing.
  val parent = SupervisorJob(frame.job)
  val pending = CoroutineScope(currentCoroutineContext() + parent).async { block(ValidationDsl(stove)) }
  parent.complete()
  awaitStalledTraffic(timeout, direction)
  return pending
}

internal class ChaosValidationScope(val coordinator: ChaosSystem, val job: Job) : AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<ChaosValidationScope>
}

/**
 * Route reservations made inside [block] survive healing until it exits. Free when chaos is not enabled;
 * nested calls share the outermost frame.
 */
internal suspend fun <T> Stove.withChaosOwnership(block: suspend () -> T): T {
  val coordinator = existingChaosSystem() ?: return block()
  if (currentCoroutineContext()[ChaosValidationScope]?.coordinator === coordinator) return block()
  return coroutineScope {
    val frame = ChaosValidationScope(coordinator, currentCoroutineContext().job)
    try {
      withContext(frame) { block() }
    } finally {
      coordinator.release(frame)
    }
  }
}

private suspend fun <T> ChaosCapableSystem.owned(block: suspend (ChaosSystem, ChaosValidationScope) -> T): T {
  val coordinator = checkNotNull(stove.existingChaosSystem()) {
    "Chaos is not enabled for this system; call enableChaos() in its setup block"
  }
  return stove.withChaosOwnership { block(coordinator, checkNotNull(currentCoroutineContext()[ChaosValidationScope])) }
}
