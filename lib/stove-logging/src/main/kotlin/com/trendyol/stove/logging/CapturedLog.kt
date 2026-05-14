package com.trendyol.stove.logging

import java.time.Instant

internal data class CapturedLog(
  val timestamp: Instant,
  val source: String,
  val level: StoveLogLevel,
  val logger: String,
  val thread: String,
  val message: String,
  val throwableType: String?,
  val throwableMessage: String?,
  val throwableStackTrace: String?,
  val mdc: Map<String, String>
)

internal interface StoveLogSink {
  fun capture(log: CapturedLog)
}
