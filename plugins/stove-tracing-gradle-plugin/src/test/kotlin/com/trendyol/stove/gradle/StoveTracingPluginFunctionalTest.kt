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
              println("profilingEnabled=${'$'}{ext.profiling.enabled.get()}")
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
    result.output shouldContain "profilingEnabled=false"
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
  test("profiling attaches to selected test JVMs independently of tracing and reuses configuration cache") {
    projectDir.resolve("build.gradle.kts").writeText(
      """
      plugins { java; id("com.trendyol.stove.tracing") }
      repositories { mavenCentral() }
      dependencies { testImplementation("junit:junit:4.13.2") }
      stoveTracing {
        enabled.set(false)
        serviceName.set("profile-service")
        testTaskNames.set(listOf("test"))
        profiling {
          enabled.set(true)
          allocations.set(true)
          uploadIntervalSeconds.set(2)
        }
      }
      """.trimIndent()
    )
    val source = projectDir.resolve("src/test/java/ProfileConfigTest.java")
    source.parentFile.mkdirs()
    source.writeText(
      """
      public class ProfileConfigTest {
        @org.junit.Test public void agentIsInstalledButWaitsForRunIdentity() throws Exception {
          org.junit.Assert.assertEquals("true", System.getProperty("stove.profiling.enabled"));
          org.junit.Assert.assertEquals("profile-service", System.getProperty("stove.profiling.service"));
          org.junit.Assert.assertEquals("512k", System.getProperty("stove.profiling.alloc"));
          org.junit.Assert.assertEquals("2", System.getProperty("stove.profiling.interval"));
          org.junit.Assert.assertEquals("false", System.getProperty("pyroscope.agent.enabled"));
          Class<?> agent = Class.forName("io.pyroscope.javaagent.PyroscopeAgent");
          org.junit.Assert.assertEquals(false, agent.getMethod("isStarted").invoke(null));
        }
      }
      """.trimIndent()
    )
    val runner = GradleRunner.create().withPluginClasspath().withProjectDir(projectDir)
      .withArguments("test", "--configuration-cache", "--rerun-tasks")
    runner.build()
    runner.build().output shouldContain "Reusing configuration cache"
  }

})
