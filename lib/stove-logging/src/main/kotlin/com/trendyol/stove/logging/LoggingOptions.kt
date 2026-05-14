package com.trendyol.stove.logging

import com.trendyol.stove.system.abstractions.SystemOptions

data class LoggingSystemOptions(
  val minLevel: StoveLogLevel = StoveLogLevel.INFO,
  val failureReportMinLevel: StoveLogLevel = StoveLogLevel.WARN,
  val maxRecordsPerTest: Int = 10_000,
  val queueCapacity: Int = 8_192,
  val maxMessageLength: Int = 16_000,
  val maxStackTraceLength: Int = 64_000,
  val includeLoggerPatterns: List<String> = emptyList(),
  val excludeLoggerPatterns: List<String> = DEFAULT_EXCLUDED_LOGGERS,
  val redactionEnabled: Boolean = true,
  val sensitiveKeySubstrings: List<String> = DEFAULT_SENSITIVE_KEYS
) : SystemOptions {
  companion object {
    val DEFAULT_EXCLUDED_LOGGERS: List<String> = listOf(
      "com\\.trendyol\\.stove\\.logging\\..*",
      "com\\.trendyol\\.stove\\.dashboard\\..*",
      "io\\.grpc\\..*",
      "io\\.netty\\..*",
      "ch\\.qos\\.logback\\..*",
      "org\\.apache\\.logging\\.log4j\\.status\\..*"
    )

    val DEFAULT_SENSITIVE_KEYS: List<String> = listOf(
      "authorization",
      "cookie",
      "password",
      "secret",
      "token",
      "apikey",
      "api_key",
      "credential"
    )
  }
}

enum class StoveLogLevel(val severityNumber: Int) {
  TRACE(1),
  DEBUG(5),
  INFO(9),
  WARN(13),
  ERROR(17);

  companion object {
    fun fromName(value: String): StoveLogLevel =
      entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: INFO
  }
}
