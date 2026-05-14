package com.trendyol.stove.logging

import java.time.Instant

data class StoveLogRecord(
  val timestamp: Instant,
  val observedTimestamp: Instant,
  val severityText: String,
  val severityNumber: Int,
  val logger: String,
  val thread: String,
  val body: String,
  val exceptionType: String? = null,
  val exceptionMessage: String? = null,
  val exceptionStackTrace: String? = null,
  val attributes: Map<String, String> = emptyMap(),
  val traceId: String? = null,
  val spanId: String? = null,
  val testId: String? = null,
  val correlationSource: LogCorrelationSource = LogCorrelationSource.UNASSIGNED,
  val source: String,
  val truncated: Boolean = false
)

data class LogsDropped(
  val timestamp: Instant,
  val testId: String?,
  val traceId: String?,
  val droppedCount: Long,
  val reason: String
)

enum class LogCorrelationSource {
  OTEL_CONTEXT,
  MDC,
  TRACE_CONTEXT,
  STOVE_TEST_CONTEXT,
  UNASSIGNED
}

interface LogEventListener {
  fun onLogRecorded(record: StoveLogRecord) {}
  fun onLogsDropped(event: LogsDropped) {}
}

interface LogListenerRegistry {
  fun addLogListener(listener: LogEventListener)
  fun removeLogListener(listener: LogEventListener)
}
