package com.trendyol.stove.testing.e2e.system.abstractions

/**
 * Represents the runtime environment for a system.
 *
 * Implementations:
 * - [com.trendyol.stove.testing.e2e.containers.StoveContainer] - container-based runtime
 * - [ProvidedRuntime] - externally provided instance
 *
 * Use pattern matching (`when`) to handle different runtime types:
 * ```kotlin
 * when (val runtime = context.runtime) {
 *   is StoveContainer -> runtime.start()
 *   is ProvidedRuntime -> // use provided config from options
 * }
 * ```
 */
interface SystemRuntime

/**
 * Provided (external) instance runtime that connects to an existing service.
 * Configuration comes from the options class.
 */
data object ProvidedRuntime : SystemRuntime
