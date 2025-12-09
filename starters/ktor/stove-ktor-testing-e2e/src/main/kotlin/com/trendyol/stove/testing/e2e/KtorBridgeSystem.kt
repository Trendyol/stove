package com.trendyol.stove.testing.e2e

import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import io.ktor.server.application.*
import kotlin.reflect.KClass

/**
 * A system that provides a bridge between the test system and the application context.
 * Supports Koin, Ktor-DI, or a custom resolver for dependency resolution.
 *
 * @property testSystem the test system to bridge.
 * @property resolver the dependency resolver function to use.
 */
@StoveDsl
class KtorBridgeSystem(
  override val testSystem: TestSystem,
  private val resolver: DependencyResolver
) : BridgeSystem<Application>(testSystem),
  PluggedSystem,
  AfterRunAwareWithContext<Application> {
  @Suppress("UNCHECKED_CAST")
  override operator fun <D : Any> get(klass: KClass<D>): D = resolver(ctx, klass) as D
}

/**
 * Registers the Ktor bridge system with automatic DI detection or a custom resolver.
 * Supports Koin and Ktor-DI out of the box.
 *
 * Example usage with auto-detect:
 * ```kotlin
 * bridge() // Auto-detects Koin or Ktor-DI
 * ```
 *
 * Example usage with custom resolver:
 * ```kotlin
 * bridge { application, klass ->
 *   application.myCustomDi.resolve(klass)
 * }
 * ```
 *
 * @param resolver a function that takes an Application and KClass and returns the resolved dependency.
 *                 Defaults to auto-detecting Koin or Ktor-DI.
 * @throws IllegalStateException if no DI framework is available and no custom resolver is provided.
 */
@StoveDsl
fun WithDsl.bridge(
  resolver: DependencyResolver = DependencyResolvers.autoDetect()
): TestSystem = this.testSystem.withBridgeSystem(KtorBridgeSystem(this.testSystem, resolver))
