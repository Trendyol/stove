package com.trendyol.stove.dashboard

import java.net.URI

/** Native profiler configuration exposed to process/container applications; does not attach a JVM agent. */
data class DashboardExternalProfilingOptions(
  val serverUrl: String,
  val serviceName: String? = null,
  val uploadIntervalSeconds: Int = 10,
  val profileTypes: List<String> = listOf("cpu")
) {
  init {
    val uri = URI.create(serverUrl)
    require(
      uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null
    ) {
      "Profiling serverUrl must be an HTTP(S) base URL without credentials, query or fragment"
    }
    require(uploadIntervalSeconds in 1..3600) { "Profiling interval must be 1..3600 seconds" }
    serviceName?.let(::requireValidProfilingServiceName)
    require(profileTypes.isNotEmpty() && profileTypes.all { it in SUPPORTED_PROFILE_TYPES }) { "Unsupported native profile type" }
  }
}

internal fun requireValidProfilingServiceName(value: String) {
  require(value.isNotBlank() && value == value.trim() && value.length <= 256 && value.all { it in ' '..'~' }) {
    "Profiling service name must be printable ASCII without surrounding whitespace, at most 256 bytes"
  }
}

private val SUPPORTED_PROFILE_TYPES = setOf(
  "cpu", "alloc_objects", "alloc_space", "inuse_objects", "inuse_space",
  "goroutines", "mutex_count", "mutex_duration", "block_count", "block_duration"
)
