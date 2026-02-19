package com.trendyol.stove.gradle.internal

/**
 * Serializable snapshot of tracing config for Gradle configuration cache compatibility.
 * All objects captured in task actions must be serializable.
 */
internal data class ResolvedTracingConfig(
  val protocol: String,
  val serviceName: String,
  val bspScheduleDelay: Int,
  val bspMaxBatchSize: Int,
  val captureHttpHeaders: Boolean,
  val captureExperimentalTelemetry: Boolean,
  val customAnnotations: List<String>,
  val disabledInstrumentations: List<String>,
  val additionalInstrumentations: List<String>,
) : java.io.Serializable {
  companion object {
    private const val serialVersionUID: Long = 1L
  }
}

internal object JvmArgsBuilder {

  fun build(agentPath: String, config: ResolvedTracingConfig, port: Int): List<String> = buildList {
    add("-javaagent:$agentPath")
    addAll(coreExportArgs(config, port))
    add("-Dotel.propagators=tracecontext,baggage")
    addAll(testOptimizationArgs(config))

    if (config.captureHttpHeaders) {
      addAll(httpHeaderCaptureArgs())
    }
    if (config.captureExperimentalTelemetry) {
      addAll(experimentalTelemetryArgs())
    }
    if (config.customAnnotations.isNotEmpty()) {
      add("-Dotel.instrumentation.annotations.methods=${config.customAnnotations.joinToString(",")}")
    }
    addAll(instrumentationControlArgs(config))
  }

  private fun coreExportArgs(config: ResolvedTracingConfig, port: Int): List<String> = buildList {
    val endpoint = "http://localhost:$port"
    add("-Dotel.traces.exporter=otlp")
    add("-Dotel.exporter.otlp.protocol=${config.protocol}")
    add("-Dotel.exporter.otlp.endpoint=$endpoint")
    add("-Dotel.metrics.exporter=none")
    add("-Dotel.logs.exporter=none")
    add("-Dotel.service.name=${config.serviceName}")
    add("-Dotel.resource.attributes=service.name=${config.serviceName},deployment.environment=test")

    if (config.protocol == "grpc") {
      add("-Dotel.instrumentation.grpc.enabled=false")
    }
  }

  private fun testOptimizationArgs(config: ResolvedTracingConfig): List<String> = listOf(
    "-Dotel.traces.sampler=always_on",
    "-Dotel.bsp.schedule.delay=${config.bspScheduleDelay}",
    "-Dotel.bsp.max.export.batch.size=${config.bspMaxBatchSize}",
  )

  private fun httpHeaderCaptureArgs(): List<String> = listOf(
    "-Dotel.instrumentation.http.client.capture-request-headers=content-type,accept,x-stove-test-id",
    "-Dotel.instrumentation.http.client.capture-response-headers=content-type",
    "-Dotel.instrumentation.http.server.capture-request-headers=content-type,accept,user-agent,x-stove-test-id",
    "-Dotel.instrumentation.http.server.capture-response-headers=content-type",
  )

  private fun experimentalTelemetryArgs(): List<String> = listOf(
    "-Dotel.instrumentation.http.client.emit-experimental-telemetry=true",
    "-Dotel.instrumentation.http.server.emit-experimental-telemetry=true",
    "-Dotel.instrumentation.servlet.experimental.capture-request-parameters=*",
  )

  private fun instrumentationControlArgs(config: ResolvedTracingConfig): List<String> = buildList {
    if (config.disabledInstrumentations.isNotEmpty()) {
      add("-Dotel.instrumentation.common.default-enabled=true")
      addAll(config.disabledInstrumentations.map { "-Dotel.instrumentation.$it.enabled=false" })
    }
    addAll(config.additionalInstrumentations.map { "-Dotel.instrumentation.$it.enabled=true" })
  }
}
