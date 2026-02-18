package com.trendyol.stove.gradle

import com.trendyol.stove.gradle.internal.TestTaskConfigurator
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Gradle plugin that configures the OpenTelemetry Java Agent for Stove test tracing.
 *
 * When a test fails, Stove can display the execution trace showing exactly
 * what happened during the test -- HTTP calls, Kafka messages, database queries, etc.
 *
 * Usage in build.gradle.kts:
 * ```kotlin
 * plugins {
 *     id("com.trendyol.stove.tracing")
 * }
 *
 * stoveTracing {
 *     serviceName.set("my-service")
 * }
 * ```
 */
class StoveTracingPlugin : Plugin<Project> {

  override fun apply(project: Project) {
    val extension = project.extensions.create("stoveTracing", StoveTracingExtension::class.java)
    TestTaskConfigurator.configure(project, extension)
  }
}
