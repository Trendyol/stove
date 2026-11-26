package com.trendyol.stove.gradle

import com.trendyol.stove.gradle.internal.StovePluginDefaults
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.util.jar.JarOutputStream

class StovePluginFunctionalTest : FunSpec({

  lateinit var projectDir: File

  beforeEach {
    projectDir = testProject()
  }

  afterEach {
    projectDir.deleteRecursively()
  }

  test("tracing alone attaches only OpenTelemetry to the selected test JVM") {
    projectDir.agentBuild(
      """
      stove {
        serviceName.set(providers.gradleProperty("service").orElse("test-service"))
        testTaskNames.set(listOf("test"))
        tracing {}
      }
      check(configurations.findByName("stoveOpenApiGenerator") == null)
      """
    )
    projectDir.javaTest("TracingTest")
    projectDir.gradle("test").build()
    projectDir.resolve("observed-service").readText() shouldBe "test-service"
    projectDir.gradle("test", "-Pservice=catalog").build().task(":test")?.outcome shouldBe TaskOutcome.SUCCESS
    projectDir.resolve("observed-service").readText() shouldBe "catalog"
  }

  test("applying the plugin leaves all features inactive") {
    projectDir.agentBuild(
      """
      check(configurations.findByName("stoveOpenApiGenerator") == null)
      check(configurations["otelAgent"].dependencies.isEmpty())
      check(configurations["stovePyroscopeAgent"].dependencies.isEmpty())
      check(!stove.tracing.enabled.get() && !stove.profiling.enabled.get())
      check(stove.serviceName.get() == "stove-traced-app")
      check(stove.tracing.protocol.get() == "grpc")
      check(stove.tracing.otelAgentVersion.get() == "2.24.0")
      """
    )
    projectDir.gradle("help").build()
  }

  test("an explicit false opts out of tracing") {
    projectDir.agentBuild(
      """
      stove {
        tracing { enabled.set(false) }
      }
      """
    )

    val result = projectDir.gradle("dependencies", "--configuration", "otelAgent").build()
    result.output shouldContain "No dependencies"

  }
  test("invalid profiling service is rejected before resolving the agent") {
    projectDir.agentBuild(
      """
      stove {
        serviceName.set(" ")
        profiling {}
      }
      """
    )
    val result = projectDir.gradle("test").buildAndFail()
    result.output shouldContain "Profiling service name must be nonblank printable ASCII and at most 256 bytes"
  }

  test("profiling attaches to selected test JVMs independently of tracing and reuses configuration cache") {
    projectDir.agentBuild(
      """
      stove {
        serviceName.set("profile-service")
        testTaskNames.set(listOf("test"))
        profiling {
          enabled.set(true)
          allocations.set(true)
          uploadIntervalSeconds.set(providers.gradleProperty("interval").map(String::toInt).orElse(2))
        }
      }
      """
    )
    projectDir.javaTest("ProfileConfigTest")
    val runner = projectDir.gradle("test", "--configuration-cache")
    runner.build()
    projectDir.resolve("observed-interval").readText() shouldBe "2"
    val unchanged = runner.build()
    unchanged.output shouldContain "Reusing configuration cache"
    unchanged.task(":test")?.outcome shouldBe TaskOutcome.UP_TO_DATE
    runner.withArguments("test", "--configuration-cache", "-Pinterval=3").build().task(":test")?.outcome shouldBe TaskOutcome.SUCCESS
    projectDir.resolve("observed-interval").readText() shouldBe "3"
  }

  test("correlation extension supports manual lifecycle with real JFR and configuration cache") {
    JarOutputStream(projectDir.resolve("other-extension.jar").outputStream()).use { }
    projectDir.agentBuild(
      """
      dependencies {
        testImplementation("io.opentelemetry:opentelemetry-api:1.58.0")
        testImplementation("io.pyroscope:agent:${StovePluginDefaults.profilingAgentVersion}")
      }
      tasks.test {
        systemProperty("otel.javaagent.extensions", file("other-extension.jar").absolutePath)
      }
      stove {
        serviceName.set("profile-service")
        testTaskNames.set(listOf("test"))
        tracing {}
        profiling {}
      }
      """
    )
    projectDir.javaTest("CorrelationTest")
    val runner = projectDir.gradle("test", "--configuration-cache", "--rerun-tasks", "--stacktrace")
    runner.build()
    runner.build().output shouldContain "Reusing configuration cache"
  }

  test("correlation can be disabled while tracing and profiling remain enabled") {
    projectDir.agentBuild(
      """
      stove {
        tracing {}
        profiling { traceCorrelation.set(false) }
      }
      """
    )
    projectDir.javaTest("OptOutTest")
    projectDir.gradle("test").build()
  }
})


private fun File.agentBuild(configuration: String) = write("build.gradle.kts", """
  plugins { java; id("com.trendyol.stove") }
  repositories { mavenCentral() }
  dependencies { testImplementation("junit:junit:4.13.2") }
""".trimIndent() + "\n" + configuration.trimIndent())

private fun File.javaTest(name: String) = write("src/test/java/$name.java", fixture("/profiling/$name.java"))
