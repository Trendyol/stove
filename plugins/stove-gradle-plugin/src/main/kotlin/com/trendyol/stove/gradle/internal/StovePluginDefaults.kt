package com.trendyol.stove.gradle.internal

import java.util.Properties

internal object StovePluginDefaults {
  private val defaults = Properties().apply {
    checkNotNull(StovePluginDefaults::class.java.getResourceAsStream("/stove-plugin.properties")).use(::load)
  }
  val profilingAgentVersion: String = defaults.getProperty("agentVersion")
  val openapiGeneratorVersion: String = defaults.getProperty("openapiGeneratorVersion")
}
