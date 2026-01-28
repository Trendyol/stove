@file:Suppress("unused")

package com.trendyol.stove.tracing

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration options for the Stove tracing system.
 *
 * The tracing system works by receiving OTLP spans from the application under test.
 * Configure your application to use the OpenTelemetry Java Agent and export spans
 * to the Stove OTLP receiver endpoint.
 */
class TracingOptions {
  var enabled: Boolean = false
    private set

  var spanCollectionTimeout: Duration = 5.seconds
    private set

  var spanFilter: (SpanInfo) -> Boolean = { true }
    private set

  var maxSpansPerTrace: Int = TracingConstants.DEFAULT_MAX_SPANS_PER_TRACE
    private set

  var serviceName: String = "stove-traced-app"
    private set

  var spanReceiverEnabled: Boolean = false
    private set

  var spanReceiverPort: Int = TracingConstants.DEFAULT_OTLP_GRPC_PORT
    private set

  fun enabled(): TracingOptions = apply { enabled = true }

  fun disabled(): TracingOptions = apply { enabled = false }

  fun spanCollectionTimeout(timeout: Duration): TracingOptions = apply {
    spanCollectionTimeout = timeout
  }

  fun spanFilter(filter: (SpanInfo) -> Boolean): TracingOptions = apply {
    spanFilter = filter
  }

  fun maxSpansPerTrace(max: Int): TracingOptions = apply {
    maxSpansPerTrace = max
  }

  fun serviceName(name: String): TracingOptions = apply {
    serviceName = name
  }

  /**
   * Enable the OTLP span receiver to collect spans from the application under test.
   *
   * The port is determined in the following order:
   * 1. Explicitly provided port parameter
   * 2. STOVE_TRACING_PORT environment variable (set by Gradle configuration)
   * 3. Default port 4317
   *
   * The application should be configured to export spans via the OpenTelemetry Java Agent:
   * ```
   * -javaagent:path/to/opentelemetry-javaagent.jar
   * -Dotel.exporter.otlp.endpoint=http://localhost:{port}
   * -Dotel.exporter.otlp.protocol=grpc
   * -Dotel.service.name=my-service
   * ```
   *
   * @param port The port for the OTLP gRPC receiver. If not specified, reads from
   *             STOVE_TRACING_PORT env var or defaults to 4317.
   */
  fun enableSpanReceiver(port: Int? = null): TracingOptions = apply {
    spanReceiverEnabled = true
    spanReceiverPort = port ?: portFromEnv() ?: TracingConstants.DEFAULT_OTLP_GRPC_PORT
  }

  private fun portFromEnv(): Int? =
    System.getenv(TracingConstants.STOVE_TRACING_PORT_ENV)?.toIntOrNull()

  fun copy(): TracingOptions = TracingOptions().also { copy ->
    copy.enabled = this.enabled
    copy.spanCollectionTimeout = this.spanCollectionTimeout
    copy.spanFilter = this.spanFilter
    copy.maxSpansPerTrace = this.maxSpansPerTrace
    copy.serviceName = this.serviceName
    copy.spanReceiverEnabled = this.spanReceiverEnabled
    copy.spanReceiverPort = this.spanReceiverPort
  }
}
