package com.trendyol.stove.gradle.internal

import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test

internal object ProfilingCorrelationConfigurator {
  fun configure(testTask: Test, extraction: TaskProvider<ExtractPyroscopeExtension>) {
    testTask.dependsOn(extraction)
    val extensionFile = extraction.flatMap { it.destination }
    testTask.inputs.file(extensionFile).withPropertyName("pyroscopeOtelExtension").withPathSensitivity(PathSensitivity.NONE)
    val extensionPath = extensionFile.get().asFile.absolutePath
    testTask.doFirst {
      // Preserve extensions supplied through Gradle properties, JVM args, or the OTel environment variable.
      val property = "otel.javaagent.extensions"
      val argumentPrefix = "-D$property="
      val arguments = testTask.jvmArgs.orEmpty()
      val existing = arguments.lastOrNull { it.startsWith(argumentPrefix) }?.removePrefix(argumentPrefix)
        ?: testTask.systemProperties[property]?.toString()
        ?: testTask.environment["OTEL_JAVAAGENT_EXTENSIONS"]?.toString()
      val paths = existing.orEmpty().split(',').filter(String::isNotBlank).plus(extensionPath).distinct()
      testTask.setJvmArgs(arguments.filterNot { it.startsWith(argumentPrefix) })
      testTask.systemProperty(property, paths.joinToString(","))
      // Stove owns run identity, the custom exporter, final flush, and shutdown.
      testTask.systemProperty("otel.pyroscope.start.profiling", "false")
      testTask.systemProperty("otel.pyroscope.root.span.only", "true")
    }
  }
}
