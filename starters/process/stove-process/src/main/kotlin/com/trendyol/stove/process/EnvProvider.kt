package com.trendyol.stove.process

import com.trendyol.stove.system.annotations.StoveDsl
import com.trendyol.stove.system.application.EnvMapperBuilder as CoreEnvMapperBuilder
import com.trendyol.stove.system.application.EnvProvider as CoreEnvProvider

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
  private val delegate = CoreEnvMapperBuilder()

  infix fun String.to(envVarName: String) {
    delegate.map(this, envVarName)
  }

  fun env(name: String, value: String) {
    delegate.env(name, value)
  }

  fun env(name: String, value: () -> String) {
    delegate.env(name, value)
  }

  internal fun build(): EnvProvider {
    val coreProvider: CoreEnvProvider = delegate.build()
    return EnvProvider { configurations ->
      coreProvider.provide(configurations)
    }
  }
}
