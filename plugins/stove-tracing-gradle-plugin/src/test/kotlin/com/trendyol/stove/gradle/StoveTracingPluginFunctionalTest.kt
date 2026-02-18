package com.trendyol.stove.gradle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import org.gradle.testkit.runner.GradleRunner
import java.io.File

class StoveTracingPluginFunctionalTest : FunSpec({

  lateinit var projectDir: File

  beforeEach {
    projectDir = File.createTempFile("stove-plugin-test", "").apply {
      delete()
      mkdirs()
    }
    projectDir.resolve("settings.gradle.kts").writeText("")
  }

  afterEach {
    projectDir.deleteRecursively()
  }

  test("plugin can be applied and extension is configurable") {
    projectDir.resolve("build.gradle.kts").writeText(
      """
      plugins {
          java
          id("com.trendyol.stove.tracing")
      }

      repositories {
          mavenCentral()
      }

      stoveTracing {
          serviceName.set("test-service")
          enabled.set(true)
          testTaskNames.set(listOf("test"))
      }
      """.trimIndent()
    )

    projectDir.resolve("src/test/java").mkdirs()

    val result = GradleRunner.create()
      .forwardOutput()
      .withPluginClasspath()
      .withArguments("tasks", "--all")
      .withProjectDir(projectDir)
      .build()

    result.output shouldContain "test"
  }

  test("plugin registers stoveTracing extension with defaults") {
    projectDir.resolve("build.gradle.kts").writeText(
      """
      plugins {
          java
          id("com.trendyol.stove.tracing")
      }

      repositories {
          mavenCentral()
      }

      tasks.register("printConfig") {
          doLast {
              val ext = project.extensions.getByType(${StoveTracingExtension::class.qualifiedName}::class.java)
              println("serviceName=${'$'}{ext.serviceName.get()}")
              println("enabled=${'$'}{ext.enabled.get()}")
              println("protocol=${'$'}{ext.protocol.get()}")
              println("otelAgentVersion=${'$'}{ext.otelAgentVersion.get()}")
          }
      }
      """.trimIndent()
    )

    val result = GradleRunner.create()
      .forwardOutput()
      .withPluginClasspath()
      .withArguments("printConfig")
      .withProjectDir(projectDir)
      .build()

    result.output shouldContain "serviceName=stove-traced-app"
    result.output shouldContain "enabled=true"
    result.output shouldContain "protocol=grpc"
    result.output shouldContain "otelAgentVersion=2.24.0"
  }

  test("plugin is disabled when enabled is set to false") {
    projectDir.resolve("build.gradle.kts").writeText(
      """
      plugins {
          java
          id("com.trendyol.stove.tracing")
      }

      repositories {
          mavenCentral()
      }

      stoveTracing {
          serviceName.set("test-service")
          enabled.set(false)
      }
      """.trimIndent()
    )

    val result = GradleRunner.create()
      .forwardOutput()
      .withPluginClasspath()
      .withArguments("tasks", "--info")
      .withProjectDir(projectDir)
      .build()

    result.output shouldContain "Stove tracing is disabled"
  }
})
