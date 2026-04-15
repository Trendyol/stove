package com.trendyol.stove.process

import com.trendyol.stove.system.annotations.StoveDsl

/**
 * Provides environment variables for a process-based application under test.
 *
 * Maps Stove's `key=value` configuration strings to environment variable names
 * that the process understands.
 *
 * ## Usage
 *
 * Use [envMapper] for a declarative builder:
 *
 * ```kotlin
 * envMapper {
 *     "database.host" to "DB_HOST"      // maps Stove config key to env var
 *     "database.port" to "DB_PORT"
 *     env("APP_ENV", "test")            // static env var
 *     env("LOG_LEVEL") { "debug" }      // computed env var
 * }
 * ```
 *
 * Or implement directly for full control:
 *
 * ```kotlin
 * EnvProvider { configs ->
 *     mapOf("DB_HOST" to configs["database.host"]!!)
 * }
 * ```
 */
fun interface EnvProvider {
  /**
   * Converts Stove configurations to environment variables.
   *
   * @param configurations Pre-parsed map of Stove config key-value pairs.
   *        For example: `{"database.host" to "localhost", "database.port" to "5432"}`.
   * @return Map of environment variable names to their values.
   */
  fun provide(configurations: Map<String, String>): Map<String, String>

  companion object {
    /**
     * An [EnvProvider] that provides no environment variables.
     */
    fun empty(): EnvProvider = EnvProvider { emptyMap() }
  }
}

/**
 * Creates an [EnvProvider] using a declarative builder DSL.
 *
 * ```kotlin
 * envMapper {
 *     "database.host" to "DB_HOST"
 *     "database.port" to "DB_PORT"
 *     env("OTEL_ENDPOINT", "localhost:4317")
 *     env("KAFKA_LIBRARY") { System.getProperty("kafka.library") ?: "sarama" }
 * }
 * ```
 *
 * @see EnvMapperBuilder
 */
fun envMapper(block: EnvMapperBuilder.() -> Unit): EnvProvider {
  val builder = EnvMapperBuilder().apply(block)
  return builder.build()
}

/**
 * Builder for [envMapper]. Maps Stove config keys to env var names and
 * adds static or computed environment variables.
 */
@StoveDsl
class EnvMapperBuilder {
  private val mappings = mutableMapOf<String, String>()
  private val staticVars = mutableMapOf<String, () -> String>()

  /**
   * Maps a Stove config key to an environment variable name.
   *
   * If the config key is present in the Stove configurations, its value
   * will be set as the environment variable. Missing keys are silently skipped.
   *
   * ```kotlin
   * "database.host" to "DB_HOST"
   * ```
   *
   * @param envVarName The environment variable name to set.
   */
  infix fun String.to(envVarName: String) {
    mappings[this] = envVarName
  }

  /**
   * Adds a static environment variable with a known value.
   *
   * ```kotlin
   * env("APP_ENV", "test")
   * ```
   */
  fun env(name: String, value: String) {
    staticVars[name] = { value }
  }

  /**
   * Adds a computed environment variable. The [value] lambda is evaluated
   * at configuration time (when `Stove().with { }` runs).
   *
   * ```kotlin
   * env("KAFKA_LIBRARY") { System.getProperty("kafka.library") ?: "sarama" }
   * ```
   */
  fun env(name: String, value: () -> String) {
    staticVars[name] = value
  }

  internal fun build(): EnvProvider = EnvProvider { configurations ->
    buildMap {
      // Map Stove config keys to env vars
      for ((configKey, envVarName) in mappings) {
        configurations[configKey]?.let { put(envVarName, it) }
      }
      // Add static/computed env vars
      for ((name, valueProvider) in staticVars) {
        put(name, valueProvider())
      }
    }
  }
}
