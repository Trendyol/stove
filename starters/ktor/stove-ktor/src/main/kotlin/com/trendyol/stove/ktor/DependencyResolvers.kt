package com.trendyol.stove.ktor

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.util.reflect.*
import org.koin.ktor.ext.getKoin
import kotlin.reflect.*

/**
 * Type alias for a dependency resolver function.
 * Takes an Application and a KType, returns the resolved dependency.
 * KType preserves generic type information (e.g., List<PaymentService>).
 */
typealias DependencyResolver = (Application, KType) -> Any

/**
 * Default resolver implementations for supported DI frameworks.
 */
object DependencyResolvers {
  /**
   * Resolver for Koin DI framework.
   */
  val koin: DependencyResolver = { application, type ->
    val klass = type.classifier as? KClass<*>
      ?: error("Cannot resolve type: $type")
    application.getKoin().get(klass)
  }

  /**
   * Resolver for Ktor-DI framework.
   * Uses full KType to preserve generic type information.
   */
  val ktorDi: DependencyResolver = { application, type ->
    require(application.attributes.contains(DependencyRegistryKey)) {
      "Ktor-DI not installed in application. Make sure to install(DI) { ... } in your application."
    }
    val klass = type.classifier as? KClass<*>
      ?: error("Cannot resolve type: $type")
    val typeInfo = TypeInfo(klass, type)
    application.dependencies.getBlocking(DependencyKey(type = typeInfo))
  }

  /**
   * Auto-detects and returns the appropriate resolver based on available DI frameworks.
   * Prefers Ktor-DI over Koin if both are active in runtime.
   * Detection is deferred to runtime to ensure Ktor application plugins are fully initialized.
   */
  fun autoDetect(): DependencyResolver = { application, type ->
    val resolver = when {
      isKtorDiActive(application) -> ktorDi
      isKoinActive(application) -> koin
      else -> error(buildNoActiveDiFrameworkMessage())
    }
    resolver(application, type)
  }

  private fun isKtorDiActive(application: Application): Boolean =
    application.attributes.contains(DependencyRegistryKey)

  private fun isKoinActive(application: Application): Boolean =
    runCatching { application.getKoin() }.isSuccess

  private fun buildNoActiveDiFrameworkMessage(): String {
    val koinOnClasspath = KtorDiCheck.isKoinAvailable()
    val ktorDiOnClasspath = KtorDiCheck.isKtorDiAvailable()

    if (!koinOnClasspath && !ktorDiOnClasspath) {
      return "No supported DI framework found. " +
        "Add either Koin (io.insert-koin:koin-ktor) or Ktor-DI (io.ktor:ktor-server-di) to your classpath, " +
        "or provide a custom resolver via bridge(resolver = { app, type -> ... })"
    }

    return "No active DI framework detected in the Ktor application runtime. " +
      "Classpath availability: Koin=$koinOnClasspath, Ktor-DI=$ktorDiOnClasspath. " +
      "Install Koin via install(Koin) { ... } and/or install Ktor-DI via dependencies { ... }, " +
      "or provide a custom resolver via bridge(resolver = { app, type -> ... })"
  }
}
