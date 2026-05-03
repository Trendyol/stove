package com.trendyol.stove.container

import com.trendyol.stove.system.ReadinessStrategy
import com.trendyol.stove.system.application.argsMapper
import com.trendyol.stove.system.application.envMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import kotlin.time.Duration.Companion.milliseconds

class ContainerApplicationUnderTestTest :
  FunSpec({
    test("builds command and env map including server port") {
      val fakeContainer = FakeContainer()
      var capturedCommand: List<String> = emptyList()
      var capturedEnv: Map<String, String> = emptyMap()
      val options = ContainerApplicationOptions(
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
        }
      )

      val aut = ContainerApplicationUnderTest(
        options = options,
        containerFactory = { fakeContainer },
        launchConfigurationObserver = { fullCommand, envVars ->
          capturedCommand = fullCommand
          capturedEnv = envVars
        }
      )

      runBlocking {
        aut.start(listOf("database.host=localhost", "database.port=5432"))
        aut.stop()
      }

      capturedCommand shouldBe listOf("worker", "--db-port=5432")
      capturedEnv shouldBe mapOf(
        "DB_HOST" to "localhost",
        "APP_PORT" to "8090"
      )
    }

    test("invokes container customizer before start") {
      val fakeContainer = FakeContainer()
      var customizerCalled = false
      val options = ContainerApplicationOptions(
        image = "busybox:latest",
        target = ContainerTarget.Worker(
          readiness = ReadinessStrategy.FixedDelay(1.milliseconds)
        ),
        configureContainer = {
          customizerCalled = true
        }
      )

      val aut = ContainerApplicationUnderTest(
        options = options,
        containerFactory = { fakeContainer },
        launchConfigurationObserver = { _, _ -> }
      )

      runBlocking {
        aut.start(emptyList())
        aut.stop()
      }

      customizerCalled shouldBe true
      fakeContainer.started shouldBe true
      fakeContainer.stopped shouldBe true
    }

    test("stop is a no-op when container was never started") {
      val aut = ContainerApplicationUnderTest(
        options = ContainerApplicationOptions(
          image = "busybox:latest",
          target = ContainerTarget.Worker()
        ),
        containerFactory = { FakeContainer() },
        launchConfigurationObserver = { _, _ -> }
      )

      runBlocking {
        aut.stop()
      }
    }
  })

private class FakeContainer : GenericContainer<FakeContainer>(DockerImageName.parse("busybox:latest")) {
  var started: Boolean = false
  var stopped: Boolean = false

  override fun start() {
    started = true
  }

  override fun stop() {
    stopped = true
  }
}
