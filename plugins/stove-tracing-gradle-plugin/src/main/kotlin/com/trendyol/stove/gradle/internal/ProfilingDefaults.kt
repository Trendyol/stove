package com.trendyol.stove.gradle.internal

import java.util.Properties

internal object ProfilingDefaults {
  private val defaults = Properties().apply {
    checkNotNull(ProfilingDefaults::class.java.getResourceAsStream("/stove-profiling.properties")).use(::load)
  }
  val agentVersion: String = defaults.getProperty("agentVersion")
}
