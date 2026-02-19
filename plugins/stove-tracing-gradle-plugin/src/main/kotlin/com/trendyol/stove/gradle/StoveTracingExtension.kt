package com.trendyol.stove.gradle

import com.trendyol.stove.gradle.internal.TracingDefaults
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Configuration DSL for the Stove Tracing Gradle plugin.
 *
 * Example usage in build.gradle.kts:
 * ```kotlin
 * stoveTracing {
 *     serviceName.set("my-service")
 *     testTaskNames.set(listOf("integrationTest"))
 * }
 * ```
 */
abstract class StoveTracingExtension @Inject constructor(objects: ObjectFactory) {

  /** The service name to use in traces. This should match your application's service name. */
  val serviceName: Property<String> = objects.property(String::class.java)
    .convention(TracingDefaults.DEFAULT_SERVICE_NAME)

  /** Whether tracing is enabled. Set false to disable tracing without removing configuration. */
  val enabled: Property<Boolean> = objects.property(Boolean::class.java)
    .convention(true)

  /**
   * The OTLP protocol to use.
   * Currently only "grpc" is supported.
   */
  val protocol: Property<String> = objects.property(String::class.java)
    .convention(TracingDefaults.DEFAULT_PROTOCOL)

  /** The batch span processor schedule delay in milliseconds. Lower = faster export. */
  val bspScheduleDelay: Property<Int> = objects.property(Int::class.java)
    .convention(TracingDefaults.DEFAULT_BSP_SCHEDULE_DELAY)

  /** The maximum batch size for span export. 1 = immediate export per span. */
  val bspMaxBatchSize: Property<Int> = objects.property(Int::class.java)
    .convention(TracingDefaults.DEFAULT_BSP_MAX_BATCH_SIZE)

  /** Whether to capture HTTP headers in spans. */
  val captureHttpHeaders: Property<Boolean> = objects.property(Boolean::class.java)
    .convention(true)

  /** Whether to enable experimental HTTP telemetry features. */
  val captureExperimentalTelemetry: Property<Boolean> = objects.property(Boolean::class.java)
    .convention(true)

  /** List of instrumentation modules to disable. Example: listOf("jdbc", "hibernate") */
  val disabledInstrumentations: ListProperty<String> = objects.listProperty(String::class.java)
    .convention(emptyList())

  /** List of additional instrumentation modules to enable. */
  val additionalInstrumentations: ListProperty<String> = objects.listProperty(String::class.java)
    .convention(emptyList())

  /** List of custom annotation class names to instrument. */
  val customAnnotations: ListProperty<String> = objects.listProperty(String::class.java)
    .convention(emptyList())

  /** The OpenTelemetry Java Agent version to use. */
  val otelAgentVersion: Property<String> = objects.property(String::class.java)
    .convention(TracingDefaults.DEFAULT_OTEL_AGENT_VERSION)

  /**
   * List of test task names to configure. If empty, applies to all test tasks.
   * Example: listOf("integrationTest") to only apply to the integrationTest task.
   */
  val testTaskNames: ListProperty<String> = objects.listProperty(String::class.java)
    .convention(emptyList())
}
