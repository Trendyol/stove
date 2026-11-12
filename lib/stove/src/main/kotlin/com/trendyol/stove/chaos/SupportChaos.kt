package com.trendyol.stove.chaos

import com.trendyol.stove.system.abstractions.SystemOptions

/** Implemented by options whose system supports managed application-link faults. */
interface SupportChaos : SystemOptions {
  val chaosOptions: ChaosOptions
}

/** Options-local opt-in, read when the system is registered. No sockets are allocated by enabling it. */
class ChaosOptions {
  var enabled: Boolean = false
    private set

  internal fun enable() {
    enabled = true
  }
}

/**
 * Enables managed chaos for systems subsequently registered with these options and returns this same instance.
 * Preserves the concrete options type, including provided-instance subclasses. Like migration configuration,
 * call this before registration; existing registrations are unaffected by later options changes.
 */
fun <O : SupportChaos> O.enableChaos(): O = apply { chaosOptions.enable() }
