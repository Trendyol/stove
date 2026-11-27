package com.trendyol.stove.gradle.internal

import com.trendyol.stove.gradle.StoveExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import java.net.ServerSocket

internal object TestTaskConfigurator {

  fun configure(project: Project, extension: StoveExtension) {
    fun agentConfiguration(name: String, purpose: String) = project.configurations.create(name) {
      isTransitive = false
      isCanBeResolved = true
      isCanBeConsumed = false
      description = purpose
    }
    val otelAgentConfig = agentConfiguration("otelAgent", "OpenTelemetry Java Agent for Stove test tracing")
    val pyroAgentConfig = agentConfiguration("stovePyroscopeAgent", "Pyroscope Java agent for Stove continuous profiling")

    project.afterEvaluate {
      val testTasks by lazy { resolveTestTasks(extension) }
      if (extension.profiling.enabled.get()) {
        dependencies.add("stovePyroscopeAgent", "io.pyroscope:agent:${extension.profiling.agentVersion.get()}")
        testTasks.forEach { ProfilingTaskConfigurator.configure(it, pyroAgentConfig, extension) }
      }
      if (!extension.tracing.enabled.get()) {
        logger.info("Stove tracing is disabled, skipping configuration")
        return@afterEvaluate
      }

      validateProtocol(extension.tracing.protocol.get())

      dependencies.add(
        "otelAgent",
        "io.opentelemetry.javaagent:opentelemetry-javaagent:${extension.tracing.otelAgentVersion.get()}"
      )

      if (extension.profiling.enabled.get() && extension.profiling.traceCorrelation.get()) {
        val correlation = tasks.register("stoveExtractPyroscopeExtension", ExtractPyroscopeExtension::class.java) {
          destination.set(layout.buildDirectory.file("stove/profiling/pyroscope-otel-javaagent-extension.jar"))
        }
        testTasks.forEach { ProfilingCorrelationConfigurator.configure(it, correlation) }
      }

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

  private fun Project.resolveTestTasks(extension: StoveExtension): List<Test> {
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
    extension: StoveExtension,
  ) {
    val resolvedAgentPath: String? = otelAgentConfig.resolve().firstOrNull()?.absolutePath

    val tracingConfig = ResolvedTracingConfig(
      protocol = extension.tracing.protocol.get(),
      serviceName = extension.serviceName.get(),
      bspScheduleDelay = extension.tracing.bspScheduleDelay.get(),
      bspMaxBatchSize = extension.tracing.bspMaxBatchSize.get(),
      captureHttpHeaders = extension.tracing.captureHttpHeaders.get(),
      captureExperimentalTelemetry = extension.tracing.captureExperimentalTelemetry.get(),
      customAnnotations = extension.tracing.customAnnotations.get(),
      disabledInstrumentations = extension.tracing.disabledInstrumentations.get(),
      additionalInstrumentations = extension.tracing.additionalInstrumentations.get(),
    )

    testTask.inputs.property("stoveTracing", tracingConfig)
    testTask.inputs.files(otelAgentConfig).withPropertyName("stoveTracingAgent").withPathSensitivity(PathSensitivity.NONE)
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

  private fun Project.logConfiguration(extension: StoveExtension, testTasks: List<Test>) {
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
