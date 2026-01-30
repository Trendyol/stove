@file:Suppress("TooManyFunctions")

package com.trendyol.stove.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.testing.Test
import java.net.ServerSocket

/*
 * ════════════════════════════════════════════════════════════════════════════════
 * STOVE TRACING CONFIGURATION
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * This file configures the OpenTelemetry Java Agent for Stove test tracing.
 * When a test fails, Stove can display the execution trace showing exactly
 * what happened during the test - HTTP calls, Kafka messages, database queries, etc.
 *
 * HOW TO USE IN YOUR PROJECT:
 * ───────────────────────────
 * 1. Copy this file to your project's buildSrc/src/main/kotlin/ directory
 *    (create the directory structure if it doesn't exist)
 *
 * 2. In your test module's build.gradle.kts, add:
 *
 *    import com.trendyol.stove.gradle.configureStoveTracing
 *
 *    configureStoveTracing {
 *        serviceName = "my-service"
 *    }
 *
 * 3. In your Stove test setup, enable tracing:
 *
 *    Stove(...)
 *        .with {
 *            tracing {
 *                enableSpanReceiver() // Port is auto-configured from STOVE_TRACING_PORT env var
 *            }
 *            // ... other systems
 *        }
 *
 *    Note: Service name is automatically extracted from incoming spans (set by OTel agent)
 *
 * CONFIGURATION OPTIONS:
 * ──────────────────────
 * - serviceName: The service name shown in traces (required)
 * - enabled: Toggle tracing on/off (default: true)
 * - protocol: grpc or http/protobuf (default: grpc)
 * - testTaskNames: Apply only to specific tasks (default: all test tasks)
 * - otelAgentVersion: OTel agent version (default: 2.24.0)
 *
 * Note: The OTLP port is dynamically assigned to avoid conflicts when running
 * parallel tests. The port is passed to tests via STOVE_TRACING_PORT env var.
 *
 * ADVANCED USAGE:
 * ───────────────
 * // Apply only to integration tests
 * configureStoveTracing {
 *     serviceName = "my-service"
 *     testTaskNames = listOf("integrationTest")
 * }
 *
 * // Custom OTel agent version
 * configureStoveTracing {
 *     serviceName = "my-service"
 *     otelAgentVersion = "2.25.0"
 * }
 *
 * // Disable specific instrumentations
 * configureStoveTracing {
 *     serviceName = "my-service"
 *     disabledInstrumentations = listOf("jdbc", "hibernate")
 * }
 *
 * ════════════════════════════════════════════════════════════════════════════════
 */

/**
 * Constants for Stove tracing configuration.
 */
private object TracingDefaults {
  const val DEFAULT_BSP_SCHEDULE_DELAY = 100
  const val DEFAULT_BSP_MAX_BATCH_SIZE = 1
  const val DEFAULT_OTEL_AGENT_VERSION = "2.24.0"
  const val DEFAULT_PROTOCOL = "grpc"

  /** Environment variable name for passing the OTLP port to tests */
  const val STOVE_TRACING_PORT_ENV = "STOVE_TRACING_PORT"
}

/**
 * Configures Stove tracing for a Gradle project.
 *
 * This function provides a simple way to set up OpenTelemetry Java Agent
 * for test tracing without needing to apply a plugin.
 *
 * Example usage in build.gradle.kts:
 * ```kotlin
 * import com.trendyol.stove.gradle.configureStoveTracing
 *
 * configureStoveTracing {
 *     serviceName = "my-service"
 * }
 * ```
 *
 * To configure only specific test tasks:
 * ```kotlin
 * configureStoveTracing {
 *     serviceName = "my-service"
 *     testTaskNames = listOf("integrationTest") // Only apply to integrationTest task
 * }
 * ```
 */
fun Project.configureStoveTracing(configure: StoveTracingConfig.() -> Unit = {}) {
  val config = StoveTracingConfig().apply(configure)

  // Create otelAgent configuration
  val otelAgentConfig = configurations.create("otelAgent").apply {
    isTransitive = false
    isCanBeResolved = true
    isCanBeConsumed = false
    description = "OpenTelemetry Java Agent for Stove test tracing"
  }

  // Add OTel agent dependency after evaluation
  afterEvaluate {
    if (!config.enabled) {
      logger.info("Stove tracing is disabled, skipping configuration")
      return@afterEvaluate
    }

    dependencies.add(
      "otelAgent",
      "io.opentelemetry.javaagent:opentelemetry-javaagent:${config.otelAgentVersion}"
    )

    // Configure test tasks
    val testTasks = resolveTestTasks(config)
    testTasks.forEach { testTask ->
      configureTestTask(testTask, otelAgentConfig, config)
    }

    logConfiguration(config, testTasks)
  }
}

private fun Project.resolveTestTasks(config: StoveTracingConfig): List<Test> =
  if (config.testTaskNames.isEmpty()) {
    tasks.withType(Test::class.java).toList()
  } else {
    config.testTaskNames.mapNotNull { taskName ->
      tasks.findByName(taskName) as? Test
    }
  }

private fun Project.logConfiguration(config: StoveTracingConfig, testTasks: List<Test>) {
  val taskInfo = if (config.testTaskNames.isEmpty()) {
    "all test tasks"
  } else {
    "tasks: ${testTasks.joinToString(", ") { it.name }}"
  }
  logger.info(
    "Stove tracing configured for service '${config.serviceName}' " +
      "with dynamic port assignment on $taskInfo"
  )
}

private fun configureTestTask(
  testTask: Test,
  otelAgentConfig: Configuration,
  config: StoveTracingConfig
) {
  // Resolve at configuration time to avoid capturing Configuration in doFirst
  // This is required for Gradle configuration cache compatibility
  val resolvedAgentPath: String? = otelAgentConfig.resolve().firstOrNull()?.absolutePath

  // Extract config values to a serializable format for configuration cache compatibility
  val tracingConfig = ResolvedTracingConfig.from(config)

  testTask.doFirst {
    if (resolvedAgentPath == null) {
      testTask.logger.warn("No OTel agent JAR found in otelAgent configuration")
      return@doFirst
    }

    // Find an available port dynamically to avoid conflicts when running multiple test tasks
    val port = findAvailablePort()
    testTask.environment(TracingDefaults.STOVE_TRACING_PORT_ENV, port.toString())

    val jvmArgs = buildJvmArgs(resolvedAgentPath, tracingConfig, port)

    testTask.jvmArgs(jvmArgs)
    testTask.logger.info(
      "Stove tracing: Attached OTel agent on port {} with {} JVM arguments",
      port,
      jvmArgs.size
    )
  }
}

private fun findAvailablePort(): Int =
  ServerSocket(0).use { it.localPort }

/**
 * Serializable copy of tracing config for Gradle configuration cache compatibility.
 * Configuration cache requires all objects captured in task actions to be serializable.
 */
private data class ResolvedTracingConfig(
  val protocol: String,
  val serviceName: String,
  val bspScheduleDelay: Int,
  val bspMaxBatchSize: Int,
  val captureHttpHeaders: Boolean,
  val captureExperimentalTelemetry: Boolean,
  val customAnnotations: List<String>,
  val disabledInstrumentations: List<String>,
  val additionalInstrumentations: List<String>
) : java.io.Serializable {
  companion object {
    private const val serialVersionUID: Long = 1L

    fun from(config: StoveTracingConfig) = ResolvedTracingConfig(
      protocol = config.protocol,
      serviceName = config.serviceName,
      bspScheduleDelay = config.bspScheduleDelay,
      bspMaxBatchSize = config.bspMaxBatchSize,
      captureHttpHeaders = config.captureHttpHeaders,
      captureExperimentalTelemetry = config.captureExperimentalTelemetry,
      customAnnotations = config.customAnnotations.toList(),
      disabledInstrumentations = config.disabledInstrumentations.toList(),
      additionalInstrumentations = config.additionalInstrumentations.toList()
    )
  }
}

private fun buildJvmArgs(agentPath: String, config: ResolvedTracingConfig, port: Int): List<String> = buildList {
  // Agent attachment
  add("-javaagent:$agentPath")

  // Core export configuration
  addAll(buildCoreExportArgs(config, port))

  // Propagation
  add("-Dotel.propagators=tracecontext,baggage")

  // Test optimization
  addAll(buildTestOptimizationArgs(config))

  // HTTP headers capture
  if (config.captureHttpHeaders) {
    addAll(buildHttpHeaderCaptureArgs())
  }

  // Experimental telemetry
  if (config.captureExperimentalTelemetry) {
    addAll(buildExperimentalTelemetryArgs())
  }

  // Custom annotations
  if (config.customAnnotations.isNotEmpty()) {
    add("-Dotel.instrumentation.annotations.methods=${config.customAnnotations.joinToString(",")}")
  }

  // Instrumentation control
  addAll(buildInstrumentationControlArgs(config))
}

private fun buildCoreExportArgs(config: ResolvedTracingConfig, port: Int): List<String> = buildList {
  val endpoint = "http://localhost:$port"
  add("-Dotel.traces.exporter=otlp")
  add("-Dotel.exporter.otlp.protocol=${config.protocol}")
  add("-Dotel.exporter.otlp.endpoint=$endpoint")
  add("-Dotel.metrics.exporter=none")
  add("-Dotel.logs.exporter=none")
  add("-Dotel.service.name=${config.serviceName}")
  add("-Dotel.resource.attributes=service.name=${config.serviceName},deployment.environment=test")

  // Disable gRPC instrumentation when using gRPC protocol to avoid instrumenting the exporter
  if (config.protocol == "grpc") {
    add("-Dotel.instrumentation.grpc.enabled=false")
  }
}

private fun buildTestOptimizationArgs(config: ResolvedTracingConfig): List<String> = listOf(
  "-Dotel.traces.sampler=always_on",
  "-Dotel.bsp.schedule.delay=${config.bspScheduleDelay}",
  "-Dotel.bsp.max.export.batch.size=${config.bspMaxBatchSize}"
)

private fun buildHttpHeaderCaptureArgs(): List<String> = listOf(
  "-Dotel.instrumentation.http.client.capture-request-headers=content-type,accept",
  "-Dotel.instrumentation.http.client.capture-response-headers=content-type",
  "-Dotel.instrumentation.http.server.capture-request-headers=content-type,accept,user-agent",
  "-Dotel.instrumentation.http.server.capture-response-headers=content-type"
)

private fun buildExperimentalTelemetryArgs(): List<String> = listOf(
  "-Dotel.instrumentation.http.client.emit-experimental-telemetry=true",
  "-Dotel.instrumentation.http.server.emit-experimental-telemetry=true",
  "-Dotel.instrumentation.servlet.experimental.capture-request-parameters=*"
)

private fun buildInstrumentationControlArgs(config: ResolvedTracingConfig): List<String> = buildList {
  if (config.disabledInstrumentations.isNotEmpty()) {
    add("-Dotel.instrumentation.common.default-enabled=true")
    addAll(config.disabledInstrumentations.map { "-Dotel.instrumentation.$it.enabled=false" })
  }
  addAll(config.additionalInstrumentations.map { "-Dotel.instrumentation.$it.enabled=true" })
}

/**
 * Configuration for Stove tracing.
 *
 * @see configureStoveTracing
 */
class StoveTracingConfig {
  /** The service name to use in traces. This should match your application's service name. */
  var serviceName: String = "stove-traced-app"

  /** Whether tracing is enabled. Set to false to disable tracing without removing configuration. */
  var enabled: Boolean = true

  /**
   * The OTLP protocol to use. Options: "grpc" or "http/protobuf".
   * Note: The port is dynamically assigned to avoid conflicts when running parallel tests.
   */
  var protocol: String = TracingDefaults.DEFAULT_PROTOCOL

  /** The batch span processor schedule delay in milliseconds. Lower = faster export. */
  var bspScheduleDelay: Int = TracingDefaults.DEFAULT_BSP_SCHEDULE_DELAY

  /** The maximum batch size for span export. 1 = immediate export per span. */
  var bspMaxBatchSize: Int = TracingDefaults.DEFAULT_BSP_MAX_BATCH_SIZE

  /** Whether to capture HTTP headers in spans. Useful for debugging request/response details. */
  var captureHttpHeaders: Boolean = true

  /** Whether to enable experimental HTTP telemetry features. */
  var captureExperimentalTelemetry: Boolean = true

  /** List of instrumentation modules to disable. Example: listOf("jdbc", "hibernate") */
  var disabledInstrumentations: List<String> = emptyList()

  /** List of additional instrumentation modules to enable. */
  var additionalInstrumentations: List<String> = emptyList()

  /** List of custom annotation class names to instrument. */
  var customAnnotations: List<String> = emptyList()

  /** The OpenTelemetry Java Agent version to use. */
  var otelAgentVersion: String = TracingDefaults.DEFAULT_OTEL_AGENT_VERSION

  /**
   * List of test task names to configure. If empty, applies to all test tasks.
   * Example: listOf("integrationTest") to only apply to the integrationTest task.
   */
  var testTaskNames: List<String> = emptyList()
}
