package com.trendyol.stove.testing.e2e

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.util.reflect.*
import org.koin.ktor.ext.getKoin
import kotlin.reflect.KClass
import kotlin.reflect.full.starProjectedType

/**
 * Type alias for a dependency resolver function.
 * Takes an Application and a KClass, returns the resolved dependency.
 */
typealias DependencyResolver = (Application, KClass<*>) -> Any

/**
 * Default resolver implementations for supported DI frameworks.
 */
object DependencyResolvers {
  /**
   * Resolver for Koin DI framework.
   */
  val koin: DependencyResolver = { application, klass -> application.getKoin().get(klass) }

  /**
   * Resolver for Ktor-DI framework.
   */
  val ktorDi: DependencyResolver = { application, klass ->
    require(application.attributes.contains(DependencyRegistryKey)) {
      "Ktor-DI not installed in application. Make sure to install(DI) { ... } in your application."
    }
    val typeInfo = TypeInfo(klass, klass.starProjectedType)
    application.dependencies.getBlocking(DependencyKey(type = typeInfo))
  }

  /**
   * Auto-detects and returns the appropriate resolver based on available DI frameworks.
   * Prefers Ktor-DI over Koin if both are available.
   * Detection is deferred to runtime to ensure classpath is fully resolved.
   */
  fun autoDetect(): DependencyResolver = { application, klass ->
    val resolver = when {
      KtorDiCheck.isKtorDiAvailable() -> ktorDi

      KtorDiCheck.isKoinAvailable() -> koin

      else -> error(
        "No supported DI framework found. " +
          "Add either Koin (io.insert-koin:koin-ktor) or Ktor-DI (io.ktor:ktor-server-di) to your classpath, " +
          "or provide a custom resolver via bridge(resolver = { app, klass -> ... })"
      )
    }
    resolver(application, klass)
  }
}
