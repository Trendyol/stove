package com.trendyol.stove.dashboard

import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.WithDsl
import com.trendyol.stove.system.annotations.StoveDsl

/**
 * Registers the Dashboard system with Stove.
 *
 * Usage:
 * ```kotlin
 * Stove { }.with {
 *   dashboard { DashboardSystemOptions(appName = "product-api") }
 *   // ... other systems
 * }
 * ```
 */
fun WithDsl.dashboard(
  configure: @StoveDsl () -> DashboardSystemOptions
): Stove {
  this.stove.getOrRegister(DashboardSystem(this.stove, configure()))
  return this.stove
}
