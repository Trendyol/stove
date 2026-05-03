package com.trendyol.stove.system.application

import com.trendyol.stove.system.annotations.StoveDsl

/**
 * Provides CLI arguments for an application under test.
 */
fun interface ArgsProvider {
  fun provide(configurations: Map<String, String>): List<String>

  companion object {
    fun empty(): ArgsProvider = ArgsProvider { emptyList() }
  }
}

fun argsMapper(
  prefix: String = "--",
  separator: String = "=",
  block: ArgsMapperBuilder.() -> Unit
): ArgsProvider =
  ArgsMapperBuilder(prefix = prefix, separator = separator).apply(block).build()

@StoveDsl
class ArgsMapperBuilder(
  private val prefix: String,
  private val separator: String
) {
  private val mappings = mutableMapOf<String, String>()
  private val staticArgs = mutableListOf<() -> List<String>>()

  fun map(configurationKey: String, flagName: String) {
    mappings[configurationKey] = flagName
  }

  infix fun String.to(flagName: String) {
    map(this, flagName)
  }

  fun arg(flag: String, value: String? = null) {
    staticArgs.add {
      if (value != null) {
        formatArg(flag, value)
      } else {
        listOf("$prefix$flag")
      }
    }
  }

  fun arg(flag: String, value: () -> String) {
    staticArgs.add { formatArg(flag, value()) }
  }

  private fun formatArg(flag: String, value: String): List<String> =
    if (separator == " ") {
      listOf("$prefix$flag", value)
    } else {
      listOf("$prefix$flag$separator$value")
    }

  fun build(): ArgsProvider = ArgsProvider { configurations ->
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
