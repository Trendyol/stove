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
  val frame = currentCoroutineContext()[ChaosValidationScope]
  check(frame != null && frame.system === this) { "duringFault must run inside this system's validation block" }
  val coordinator = stove.existingChaosSystem()
  check(coordinator != null) { "Chaos is not enabled for this system; call enableChaos() in its setup block" }
  return coordinator.duringFault(frame, fault) { block(ValidationDsl(stove)) }
}

internal class ChaosValidationScope(val system: ChaosCapableSystem, val job: Job) :
  AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<ChaosValidationScope>
}
