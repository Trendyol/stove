package com.trendyol.stove.tracing

/**
 * Constants used throughout the tracing module.
 * Centralizes magic numbers and configuration defaults.
 */
object TracingConstants {
  /** Default gRPC port for OTLP protocol */
  const val DEFAULT_OTLP_GRPC_PORT = 4317

  /** Environment variable name for OTLP port (set by Gradle configuration) */
  const val STOVE_TRACING_PORT_ENV = "STOVE_TRACING_PORT"

  /** Default HTTP port for OTLP protocol */
  const val DEFAULT_OTLP_HTTP_PORT = 4318

  /** Nanoseconds to milliseconds conversion factor */
  const val NANOS_TO_MILLIS = 1_000_000L

  /** Default polling interval when waiting for spans (milliseconds) */
  const val DEFAULT_SPAN_POLL_INTERVAL_MS = 50L

  /** Default timeout when waiting for spans (milliseconds) */
  const val DEFAULT_SPAN_WAIT_TIMEOUT_MS = 2000L

  /** Additional wait time for straggler spans after first span arrives (milliseconds) */
  const val STRAGGLER_WAIT_TIME_MS = 200L

  /** Server shutdown grace period (seconds) */
  const val SERVER_SHUTDOWN_TIMEOUT_SECONDS = 5L

  /** Default maximum spans per trace to prevent memory issues */
  const val DEFAULT_MAX_SPANS_PER_TRACE = 1000

  /** Maximum stack trace lines to display in trace trees */
  const val MAX_STACK_TRACE_LINES = 3

  /** OpenTelemetry status code for ERROR */
  const val OTEL_STATUS_CODE_ERROR = 2

  /** Service name attribute key in OpenTelemetry */
  const val OTEL_SERVICE_NAME_ATTRIBUTE = "service.name"

  /** gRPC internal span patterns to filter out */
  val GRPC_INTERNAL_SPAN_PATTERNS = listOf(
    "TraceService/Export",
    "opentelemetry.proto.collector"
  )

  /** OpenTelemetry exception event name */
  const val OTEL_EXCEPTION_EVENT_NAME = "exception"

  /** OpenTelemetry exception type attribute key */
  const val OTEL_EXCEPTION_TYPE_ATTRIBUTE = "exception.type"

  /** OpenTelemetry exception message attribute key */
  const val OTEL_EXCEPTION_MESSAGE_ATTRIBUTE = "exception.message"

  /** OpenTelemetry exception stacktrace attribute key */
  const val OTEL_EXCEPTION_STACKTRACE_ATTRIBUTE = "exception.stacktrace"
}
