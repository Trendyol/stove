package com.trendyol.stove.tracing

/**
 * Information about a single span in a trace.
 */
data class SpanInfo(
  val traceId: String,
  val spanId: String,
  val parentSpanId: String?,
  val operationName: String,
  val serviceName: String,
  val startTimeNanos: Long,
  val endTimeNanos: Long,
  val status: SpanStatus,
  val attributes: Map<String, String> = emptyMap(),
  val exception: ExceptionInfo? = null
) {
  val durationMs: Long
    get() = (endTimeNanos - startTimeNanos) / NANOS_TO_MILLIS

  val durationNanos: Long
    get() = endTimeNanos - startTimeNanos

  val isFailed: Boolean
    get() = status == SpanStatus.ERROR

  val isSuccess: Boolean
    get() = status == SpanStatus.OK

  companion object {
    internal const val NANOS_TO_MILLIS = 1_000_000L
  }
}

/**
 * Exception information captured in a span.
 */
data class ExceptionInfo(
  val type: String,
  val message: String,
  val stackTrace: List<String> = emptyList()
)

/**
 * Status of a span.
 */
enum class SpanStatus {
  OK,
  ERROR,
  UNSET
}
