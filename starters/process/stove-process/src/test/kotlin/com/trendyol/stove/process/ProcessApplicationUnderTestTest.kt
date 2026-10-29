package com.trendyol.stove.process

import com.trendyol.stove.system.ReadinessStrategy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProcessApplicationUnderTestTest : FunSpec({
  test("passes mapped environment, server port, and arguments to the application") {
    ProcessFixture().use { fixture ->
      val options = fixture.options(
        $$"""echo "$APP_PORT|$DB_HOST|$APP_ENV|$COMPUTED|$2|$3" > "$1"; exec sleep 30"""
      ).copy(
        target = ProcessTarget.Server(port = 8090, portEnvVar = "APP_PORT", readiness = fixture.readiness),
        envProvider = envMapper {
          "database.host" to "DB_HOST"
          env("APP_PORT", "overridden-by-target")
          env("APP_ENV", "test")
          env("COMPUTED") { "hello" }
        },
        argsProvider = argsMapper {
          "database.port" to "db-port"
          arg("verbose")
        }
      )

      fixture.start(options, listOf("database.host=localhost", "database.port=5432"))

      fixture.output.readText().trim() shouldBe "8090|localhost|test|hello|--db-port=5432|--verbose"
    }
  }

  test("pre-start callback receives configurations and runs before the child in its working directory") {
    ProcessFixture().use { fixture ->
      lateinit var capturedOptions: ProcessApplicationOptions
      lateinit var capturedConfigurations: Map<String, String>
      val options = fixture.options($$"""pwd > "$1"; exec sleep 30""").copy(
        workingDirectory = fixture.directory,
        beforeStarted = { configurations, applicationOptions ->
          fixture.output.exists() shouldBe false
          capturedConfigurations = configurations
          capturedOptions = applicationOptions
        }
      )

      fixture.start(options, listOf("database.host=localhost", "database.port=5432"))

      capturedConfigurations shouldBe mapOf("database.host" to "localhost", "database.port" to "5432")
      capturedOptions shouldBe options
      File(fixture.output.readText().trim()).canonicalFile shouldBe fixture.directory.canonicalFile
    }
  }

  test("workers retain their configured environment without server port injection") {
    ProcessFixture().use { fixture ->
      val options = fixture.options($$"""echo "$APP_PORT" > "$1"; exec sleep 30""").copy(
        envProvider = envMapper { env("APP_PORT", "worker-value") }
      )

      fixture.start(options)

      fixture.output.readText().trim() shouldBe "worker-value"
    }
  }

  test("stop terminates the application process and can be called again") {
    ProcessFixture().use { fixture ->
      fixture.start(fixture.options($$"""echo "$$" > "$1"; exec sleep 30"""))
      val pid = fixture.output.readText().trim().toLong()
      ProcessHandle.of(pid).orElseThrow().isAlive shouldBe true

      fixture.stop()
      fixture.stop()

      ProcessHandle.of(pid).map { it.isAlive }.orElse(false) shouldBe false
    }
  }

  test("stop is safe before an application has started") {
    ProcessFixture().use { fixture ->
      ProcessApplicationUnderTest(fixture.options("exit 0")).stop()
    }
  }

  test("startup fails when the application never becomes ready") {
    ProcessFixture().use { fixture ->
      val options = fixture.options("exit 1").copy(
        target = ProcessTarget.Worker(
          readiness = ReadinessStrategy.Probe(retries = 2, retryDelay = 1.milliseconds) { false }
        )
      )

      shouldThrow<IllegalStateException> { fixture.start(options) }
    }
  }
})

private class ProcessFixture : AutoCloseable {
  val directory: File = createTempDirectory("stove-process-test").toFile()
  val output: File = directory.resolve("output.txt")
  val readiness = ReadinessStrategy.Probe(retries = 100, retryDelay = 20.milliseconds) {
    output.exists() && output.length() > 0
  }
  private var application: ProcessApplicationUnderTest? = null

  fun options(script: String): ProcessApplicationOptions = ProcessApplicationOptions(
    command = listOf("sh", "-c", script, "stove-test", output.absolutePath),
    target = ProcessTarget.Worker(readiness = readiness),
    gracefulShutdownTimeout = 5.seconds
  )

  suspend fun start(options: ProcessApplicationOptions, configurations: List<String> = emptyList()) {
    val runner = ProcessApplicationUnderTest(options)
    application = runner
    runner.start(configurations)
  }

  suspend fun stop() {
    application?.stop()
  }

  override fun close() {
    try {
      runBlocking { stop() }
    } finally {
      directory.deleteRecursively()
    }
  }
}
