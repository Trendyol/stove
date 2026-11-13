package com.trendyol.stove.chaos

import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.WithDsl
import com.trendyol.stove.system.abstractions.PluggedSystem
import com.trendyol.stove.system.annotations.StoveDsl

/** Implemented by modules with an application endpoint adapter. */
@StoveDsl
interface ChaosCapableSystem : PluggedSystem

/**
 * Rewrites only the application-facing configuration; native clients retain the original configuration.
 * Pass every endpoint the application dials through [proxy] and return the configuration pointing at the results.
 * Throw [IllegalArgumentException] for configurations that cannot be routed.
 */
fun interface ChaosEndpointAdapter<C> {
  fun reroute(direct: C, proxy: (TcpEndpoint) -> TcpEndpoint): C
}

/**
 * Module setup hook: reads the options' chaos setting, registers the system, then enables routing if requested.
 * [register] receives whether chaos was requested so it can reject options that cannot be routed.
 */
fun <O : SupportChaos> WithDsl.registerChaosCapable(
  configure: () -> O,
  register: (options: O, chaosEnabled: Boolean) -> ChaosCapableSystem
): Stove {
  val options = configure()
  val chaosEnabled = options.chaosOptions.enabled
  val system = register(options, chaosEnabled)
  if (chaosEnabled) system.enableChaosRouting()
  return stove
}

internal fun ChaosCapableSystem.enableChaosRouting() {
  stove.chaosSystem().enable(this)
}

/** Module configuration hook; disabled systems return the original configuration unchanged. */
fun <C> ChaosCapableSystem.applicationConfiguration(direct: C, adapter: ChaosEndpointAdapter<C>): C =
  stove.existingChaosSystem()?.route(this, direct, adapter) ?: direct
