package com.trendyol.stove.system.application

import com.trendyol.stove.system.annotations.StoveDsl

/**
 * Provides environment variables for an application under test.
 */
fun interface EnvProvider {
  fun provide(configurations: Map<String, String>): Map<String, String>

  companion object {
    fun empty(): EnvProvider = EnvProvider { emptyMap() }
  }
}

fun envMapper(block: EnvMapperBuilder.() -> Unit): EnvProvider =
  EnvMapperBuilder().apply(block).build()

@StoveDsl
class EnvMapperBuilder {
  private val mappings = mutableMapOf<String, String>()
  private val staticVars = mutableMapOf<String, () -> String>()

  fun map(configurationKey: String, envVarName: String) {
    mappings[configurationKey] = envVarName
  }

  infix fun String.to(envVarName: String) {
    map(this, envVarName)
  }

  fun env(name: String, value: String) {
    staticVars[name] = { value }
  }

  fun env(name: String, value: () -> String) {
    staticVars[name] = value
  }

  fun build(): EnvProvider = EnvProvider { configurations ->
    buildMap {
      for ((configKey, envVarName) in mappings) {
        configurations[configKey]?.let { put(envVarName, it) }
      }
      for ((name, valueProvider) in staticVars) {
        put(name, valueProvider())
      }
    }
  }
}
