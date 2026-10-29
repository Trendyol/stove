package com.trendyol.stove.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configureEach
import org.gradle.kotlin.dsl.registerIfAbsent
import org.gradle.kotlin.dsl.withType

/** Shared test scheduling and JVM isolation for the main build and JVM recipes. */
class StoveTestConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) = with(target) {
    val cpuBudget = providers.positiveIntProperty("stove.test.cpuBudget")
      .getOrElse((Runtime.getRuntime().availableProcessors() * 0.8).toInt().coerceAtLeast(1))
    // Prefer modules by default; focused runs can reserve capacity for Kotest.
    val moduleSlots = providers.positiveIntProperty("stove.test.parallelModules")
      .getOrElse(cpuBudget)
      .coerceAtMost(cpuBudget)
      .coerceAtMost(gradle.startParameter.maxWorkerCount)
    val jvmSlots = (cpuBudget / moduleSlots).coerceAtLeast(1)
    val concurrencySlots = gradle.sharedServices.registerIfAbsent("stoveTestConcurrency", TestConcurrencySlots::class) {
      maxParallelUsages.set(moduleSlots)
    }

    tasks.withType<Test>().configureEach {
      // Instrumented forks would inherit the same tracing endpoint.
      maxParallelForks = 1
      usesService(concurrencySlots)
      systemProperty("stove.test.concurrency", jvmSlots)
      doFirst {
        val isolatedTemp = temporaryDir.resolve("tmp").apply { mkdirs() }
        systemProperty("java.io.tmpdir", isolatedTemp.absolutePath)
      }
    }
  }
}

abstract class TestConcurrencySlots : BuildService<BuildServiceParameters.None>

private fun ProviderFactory.positiveIntProperty(name: String) = gradleProperty(name).map { value ->
  requireNotNull(value.toIntOrNull()?.takeIf { it > 0 }) { "$name must be a positive integer, got '$value'" }
}
