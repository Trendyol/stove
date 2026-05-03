package com.trendyol.stove.process

import com.trendyol.stove.system.annotations.StoveDsl
import com.trendyol.stove.system.application.ArgsMapperBuilder as CoreArgsMapperBuilder
import com.trendyol.stove.system.application.ArgsProvider as CoreArgsProvider

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
  ArgsMapperBuilder(prefix, separator).apply(block).build()

@StoveDsl
class ArgsMapperBuilder(
  private val prefix: String,
  private val separator: String
) {
  private val delegate = CoreArgsMapperBuilder(prefix = prefix, separator = separator)

  infix fun String.to(flagName: String) {
    delegate.map(this, flagName)
  }

  fun arg(flag: String, value: String? = null) {
    delegate.arg(flag, value)
  }

  fun arg(flag: String, value: () -> String) {
    delegate.arg(flag, value)
  }

  internal fun build(): ArgsProvider {
    val coreProvider: CoreArgsProvider = delegate.build()
    return ArgsProvider { configurations ->
      coreProvider.provide(configurations)
    }
  }
}
