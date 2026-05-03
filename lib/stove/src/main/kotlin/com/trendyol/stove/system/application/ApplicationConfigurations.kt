package com.trendyol.stove.system.application

/**
 * Parses Stove `key=value` configuration entries into a map for application launch.
 */
fun List<String>.toConfigurationMap(): Map<String, String> =
  associate { line ->
    val (key, value) = line.split("=", limit = 2)
    key to value
  }
