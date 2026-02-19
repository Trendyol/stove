package com.trendyol.stove.gradle.internal

internal object TracingDefaults {
  const val DEFAULT_BSP_SCHEDULE_DELAY = 100
  const val DEFAULT_BSP_MAX_BATCH_SIZE = 1
  const val DEFAULT_OTEL_AGENT_VERSION = "2.24.0"
  const val DEFAULT_PROTOCOL = "grpc"
  const val SUPPORTED_PROTOCOL = DEFAULT_PROTOCOL
  const val DEFAULT_SERVICE_NAME = "stove-traced-app"
  const val STOVE_TRACING_PORT_ENV = "STOVE_TRACING_PORT"
}
