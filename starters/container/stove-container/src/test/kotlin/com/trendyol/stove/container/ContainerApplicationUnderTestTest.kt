package com.trendyol.stove.container

import com.trendyol.stove.system.ReadinessStrategy
import com.trendyol.stove.system.application.argsMapper
import com.trendyol.stove.system.application.envMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import kotlin.time.Duration.Companion.milliseconds

class ContainerApplicationUnderTestTest :
  FunSpec({
    test("builds command and env map including server port") {
      val fakeContainer = FakeContainer()
      var capturedCommand: List<String> = emptyList()
      var capturedEnv: Map<String, String> = emptyMap()

      val aut = ContainerApplicationUnderTest(
        image = "busybox:latest",
        command = listOf("worker"),
        target = ContainerTarget.Server(
          hostPort = 18090,
          internalPort = 8090,
          portEnvVar = "APP_PORT",
          readiness = ReadinessStrategy.FixedDelay(1.milliseconds)
        ),
        envProvider = envMapper {
          "database.host" to "DB_HOST"
        },
        argsProvider = argsMapper(prefix = "--", separator = "=") {
          "database.port" to "db-port"
        },
        containerFactory = { fakeContainer },
        launchConfigurationObserver = { fullCommand, envVars ->
          capturedCommand = fullCommand
          capturedEnv = envVars
        }
      )

      aut.start(listOf("database.host=localhost", "database.port=5432"))
      aut.stop()

      capturedCommand shouldBe listOf("worker", "--db-port=5432")
      capturedEnv shouldBe mapOf(
        "DB_HOST" to "localhost",
        "APP_PORT" to "8090"
      )
    }

    test("invokes container customizer before start") {
      val fakeContainer = FakeContainer()
      var customizerCalled = false

      val aut = ContainerApplicationUnderTest(
        image = "busybox:latest",
        target = ContainerTarget.Worker(
          readiness = ReadinessStrategy.FixedDelay(1.milliseconds)
        ),
        configureContainer = {
          fakeContainer.started shouldBe false
          customizerCalled = true
        },
        containerFactory = { fakeContainer },
        launchConfigurationObserver = { _, _ -> }
      )

      aut.start(emptyList())
      aut.stop()

      customizerCalled shouldBe true
      fakeContainer.started shouldBe true
      fakeContainer.stopped shouldBe true
    }

    test("startup errors include container logs and retain the cause") {
      val failure = IllegalArgumentException("image failed")
      val container = FakeContainer(startFailure = failure)
      val aut = ContainerApplicationUnderTest(
        image = "busybox:latest",
        target = ContainerTarget.Worker(),
        containerFactory = { container },
        launchConfigurationObserver = { _, _ -> }
      )

      val thrown = shouldThrow<IllegalStateException> { aut.start(emptyList()) }

      thrown.cause?.message shouldBe failure.message
      thrown.message shouldContain "busybox:latest"
      thrown.message shouldContain "application startup log"
    }

    test("failed readiness stops the container and later stop is a no-op") {
      val container = FakeContainer()
      val aut = ContainerApplicationUnderTest(
        image = "busybox:latest",
        target = ContainerTarget.Worker(
          readiness = ReadinessStrategy.Probe(retries = 1, retryDelay = 1.milliseconds) { false }
        ),
        containerFactory = { container },
        launchConfigurationObserver = { _, _ -> }
      )

      shouldThrow<IllegalStateException> { aut.start(emptyList()) }
      aut.stop()

      container.started shouldBe true
      container.stopCalls shouldBe 1
    }

    test("stop is a no-op when container was never started") {
      val aut = ContainerApplicationUnderTest(
        image = "busybox:latest",
        target = ContainerTarget.Worker(),
        containerFactory = { FakeContainer() },
        launchConfigurationObserver = { _, _ -> }
      )

      aut.stop()
    }
  })

private class FakeContainer(
  private val startFailure: Throwable? = null
) : GenericContainer<FakeContainer>(DockerImageName.parse("busybox:latest")) {
  var started: Boolean = false
  var stopCalls: Int = 0
  val stopped: Boolean get() = stopCalls > 0

  override fun start() {
    startFailure?.let { throw it }
    started = true
  }

  override fun getLogs(): String = "application startup log"

  override fun stop() {
    stopCalls++
  }
}
