@file:Suppress("TooGenericExceptionCaught", "SwallowedException")

package com.trendyol.stove.testing.e2e

/**
 * Checks which DI system is available on the classpath.
 */
object KtorDiCheck {
  /**
   * Returns true if Koin is available on the classpath.
   */
  fun isKoinAvailable(): Boolean = try {
    Class.forName("org.koin.ktor.ext.ApplicationExtKt")
    true
  } catch (_: ClassNotFoundException) {
    false
  }

  /**
   * Returns true if Ktor-DI is available on the classpath.
   */
  fun isKtorDiAvailable(): Boolean = try {
    Class.forName("io.ktor.server.plugins.di.DependencyInjectionConfig")
    true
  } catch (_: ClassNotFoundException) {
    false
  }
}
