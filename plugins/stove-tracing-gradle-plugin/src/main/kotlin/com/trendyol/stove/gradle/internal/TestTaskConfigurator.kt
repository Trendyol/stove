package com.trendyol.stove.gradle.internal

import com.trendyol.stove.gradle.StoveTracingExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.testing.Test
import java.net.ServerSocket

internal object TestTaskConfigurator {

  fun configure(project: Project, extension: StoveTracingExtension) {
    val otelAgentConfig = project.configurations.create("otelAgent") {
      isTransitive = false
      isCanBeResolved = true
      isCanBeConsumed = false
      description = "OpenTelemetry Java Agent for Stove test tracing"
    }

    project.afterEvaluate {
      if (!extension.enabled.get()) {
        logger.info("Stove tracing is disabled, skipping configuration")
        return@afterEvaluate
      }

      validateProtocol(extension.protocol.get())

      dependencies.add(
        "otelAgent",
        "io.opentelemetry.javaagent:opentelemetry-javaagent:${extension.otelAgentVersion.get()}"
      )

      val testTasks = resolveTestTasks(extension)
      testTasks.forEach { testTask ->
        configureTestTask(testTask, otelAgentConfig, extension)
      }

      logConfiguration(extension, testTasks)
    }
  }

  private fun validateProtocol(protocol: String) {
    require(protocol == TracingDefaults.SUPPORTED_PROTOCOL) {
      "Unsupported OTLP protocol '$protocol'. Stove tracing receiver currently supports only " +
        "'${TracingDefaults.SUPPORTED_PROTOCOL}'."
    }
  }

  private fun Project.resolveTestTasks(extension: StoveTracingExtension): List<Test> {
    val taskNames = extension.testTaskNames.get()
    return if (taskNames.isEmpty()) {
      tasks.withType(Test::class.java).toList()
    } else {
      taskNames.mapNotNull { taskName -> tasks.findByName(taskName) as? Test }
    }
  }

  private fun configureTestTask(
    testTask: Test,
    otelAgentConfig: Configuration,
    extension: StoveTracingExtension,
  ) {
    val resolvedAgentPath: String? = otelAgentConfig.resolve().firstOrNull()?.absolutePath

    val tracingConfig = ResolvedTracingConfig(
      protocol = extension.protocol.get(),
      serviceName = extension.serviceName.get(),
      bspScheduleDelay = extension.bspScheduleDelay.get(),
      bspMaxBatchSize = extension.bspMaxBatchSize.get(),
      captureHttpHeaders = extension.captureHttpHeaders.get(),
      captureExperimentalTelemetry = extension.captureExperimentalTelemetry.get(),
      customAnnotations = extension.customAnnotations.get(),
      disabledInstrumentations = extension.disabledInstrumentations.get(),
      additionalInstrumentations = extension.additionalInstrumentations.get(),
    )

    testTask.doFirst {
      if (resolvedAgentPath == null) {
        testTask.logger.warn("No OTel agent JAR found in otelAgent configuration")
        return@doFirst
      }

      val port = findAvailablePort()
      testTask.environment(TracingDefaults.STOVE_TRACING_PORT_ENV, port.toString())

      val jvmArgs = JvmArgsBuilder.build(resolvedAgentPath, tracingConfig, port)
      testTask.jvmArgs(jvmArgs)
      testTask.logger.info(
        "Stove tracing: Attached OTel agent on port {} with {} JVM arguments",
        port,
        jvmArgs.size,
      )
    }
  }

  private fun Project.logConfiguration(extension: StoveTracingExtension, testTasks: List<Test>) {
    val taskNames = extension.testTaskNames.get()
    val taskInfo = if (taskNames.isEmpty()) {
      "all test tasks"
    } else {
      "tasks: ${testTasks.joinToString(", ") { it.name }}"
    }
    logger.info(
      "Stove tracing configured for service '${extension.serviceName.get()}' " +
        "with dynamic port assignment on $taskInfo"
    )
  }

  private fun findAvailablePort(): Int = ServerSocket(0).use { it.localPort }
}
