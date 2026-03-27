package com.trendyol.stove.portal

import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.WithDsl
import com.trendyol.stove.system.annotations.StoveDsl

/**
 * Registers the Portal system with Stove.
 *
 * Usage:
 * ```kotlin
 * Stove { }.with {
 *   portal { PortalSystemOptions(appName = "product-api") }
 *   // ... other systems
 * }
 * ```
 */
fun WithDsl.portal(
  configure: @StoveDsl () -> PortalSystemOptions
): Stove {
  this.stove.getOrRegister(PortalSystem(this.stove, configure()))
  return this.stove
}
