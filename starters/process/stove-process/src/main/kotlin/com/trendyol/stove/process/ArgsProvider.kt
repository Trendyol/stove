package com.trendyol.stove.process

import com.trendyol.stove.system.annotations.StoveDsl

/**
 * Provides CLI arguments for a process-based application under test.
 *
 * Maps Stove's `key=value` configuration strings to command-line arguments
 * that are appended to the process command.
 *
 * ## Usage
 *
 * Use [argsMapper] for a declarative builder:
 *
 * ```kotlin
 * // --db-host=localhost --db-port=5432
 * argsMapper(prefix = "--", separator = "=") {
 *     "database.host" to "db-host"
 *     "database.port" to "db-port"
 * }
 *
 * // --db-host localhost --db-port 5432
 * argsMapper(prefix = "--", separator = " ") {
 *     "database.host" to "db-host"
 *     "database.port" to "db-port"
 * }
 * ```
 *
 * Or implement directly for full control:
 *
 * ```kotlin
 * ArgsProvider { configs ->
 *     listOf("--db-host", configs["database.host"]!!)
 * }
 * ```
 */
fun interface ArgsProvider {
  /**
   * Converts Stove configurations to CLI arguments.
   *
   * @param configurations Pre-parsed map of Stove config key-value pairs.
   * @return List of CLI argument strings to append to the process command.
   */
  fun provide(configurations: Map<String, String>): List<String>

  companion object {
    /**
     * An [ArgsProvider] that provides no CLI arguments.
     */
    fun empty(): ArgsProvider = ArgsProvider { emptyList() }
  }
}

/**
 * Creates an [ArgsProvider] using a declarative builder DSL.
 *
 * ## Separator behavior
 *
 * - `separator = "="` produces a single arg: `--flag=value`
 * - `separator = " "` produces two separate args: `--flag`, `value`
 * - Any other separator produces a single arg: `--flag<sep>value`
 *
 * ## Examples
 *
 * ```kotlin
 * // GNU-style: --db-host=localhost --db-port=5432
 * argsMapper(prefix = "--", separator = "=") {
 *     "database.host" to "db-host"
 *     "database.port" to "db-port"
 * }
 *
 * // POSIX-style: -h localhost -p 5432
 * argsMapper(prefix = "-", separator = " ") {
 *     "database.host" to "h"
 *     "database.port" to "p"
 * }
 *
 * // No prefix: db-host=localhost
 * argsMapper(prefix = "", separator = "=") {
 *     "database.host" to "db-host"
 * }
 * ```
 *
 * @param prefix Prefix for each flag (e.g., `"--"`, `"-"`, `""`). Defaults to `"--"`.
 * @param separator Separator between flag and value. `" "` splits into two args. Defaults to `"="`.
 * @see ArgsMapperBuilder
 */
fun argsMapper(
  prefix: String = "--",
  separator: String = "=",
  block: ArgsMapperBuilder.() -> Unit
): ArgsProvider {
  val builder = ArgsMapperBuilder(prefix, separator).apply(block)
  return builder.build()
}

/**
 * Builder for [argsMapper]. Maps Stove config keys to CLI flag names and
 * adds static or computed CLI arguments.
 */
@StoveDsl
class ArgsMapperBuilder(
  private val prefix: String,
  private val separator: String
) {
  private val mappings = mutableMapOf<String, String>()
  private val staticArgs = mutableListOf<() -> List<String>>()

  /**
   * Maps a Stove config key to a CLI flag name.
   *
   * If the config key is present in the Stove configurations, its value
   * will be appended as a CLI argument. Missing keys are silently skipped.
   *
   * ```kotlin
   * "database.host" to "db-host"  // becomes --db-host=localhost
   * ```
   *
   * @param flagName The CLI flag name (without prefix).
   */
  infix fun String.to(flagName: String) {
    mappings[this] = flagName
  }

  /**
   * Adds a static CLI argument with a known value.
   *
   * ```kotlin
   * arg("verbose")         // becomes --verbose
   * arg("log-level", "debug")  // becomes --log-level=debug
   * ```
   */
  fun arg(flag: String, value: String? = null) {
    staticArgs.add {
      if (value != null) {
        formatArg(flag, value)
      } else {
        listOf("$prefix$flag")
      }
    }
  }

  /**
   * Adds a computed CLI argument.
   *
   * ```kotlin
   * arg("config-file") { "/tmp/test-config.yaml" }
   * ```
   */
  fun arg(flag: String, value: () -> String) {
    staticArgs.add { formatArg(flag, value()) }
  }

  private fun formatArg(flag: String, value: String): List<String> =
    if (separator == " ") {
      listOf("$prefix$flag", value)
    } else {
      listOf("$prefix$flag$separator$value")
    }

  internal fun build(): ArgsProvider = ArgsProvider { configurations ->
    buildList {
      for ((configKey, flagName) in mappings) {
        configurations[configKey]?.let { value ->
          addAll(formatArg(flagName, value))
        }
      }
      for (provider in staticArgs) {
        addAll(provider())
      }
    }
  }
}
