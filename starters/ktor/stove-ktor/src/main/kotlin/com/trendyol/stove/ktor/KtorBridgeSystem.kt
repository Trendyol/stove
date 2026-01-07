@file:Suppress("UNCHECKED_CAST")

package com.trendyol.stove.ktor

import com.trendyol.stove.system.*
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl
import io.ktor.server.application.*
import kotlin.reflect.*
import kotlin.reflect.full.starProjectedType

/**
 * A system that provides a bridge between the test system and the application context.
 * Supports Koin, Ktor-DI, or a custom resolver for dependency resolution.
 *
 * @property stove the test system to bridge.
 * @property resolver the dependency resolver function to use.
 */
@StoveDsl
class KtorBridgeSystem(
  override val stove: Stove,
  private val resolver: DependencyResolver
) : BridgeSystem<Application>(stove),
  PluggedSystem,
  AfterRunAwareWithContext<Application> {
  /**
   * Resolves a dependency by KClass (fallback, loses generic info).
   */
  override fun <D : Any> get(klass: KClass<D>): D = resolver(ctx, klass.starProjectedType) as D

  /**
   * Resolves a dependency by KType, preserving generic type information.
   * This allows resolving types like List<PaymentService> correctly.
   */
  override fun <D : Any> getByType(type: KType): D = resolver(ctx, type) as D
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
): Stove = this.stove.withBridgeSystem(KtorBridgeSystem(this.stove, resolver))
