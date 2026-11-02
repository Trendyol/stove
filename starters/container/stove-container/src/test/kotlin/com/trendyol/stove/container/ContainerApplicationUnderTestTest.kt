package com.trendyol.stove.container

import com.trendyol.stove.system.ReadinessStrategy
import com.trendyol.stove.system.application.argsMapper
import com.trendyol.stove.system.application.envMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import kotlin.time.Duration.Companion.milliseconds

class ContainerApplicationUnderTestTest :
  FunSpec({
    test("graceful stop lets the application flush before container removal") {
      var finalLogs = ""
      val container = object : GenericContainer<Nothing>(DockerImageName.parse("alpine:3.24")) {
        override fun stop() {
          finalLogs = logs
          super.stop()
        }
      }
      container.waitingFor(Wait.forLogMessage(".*ready.*", 1))
      val aut = ContainerApplicationUnderTest(
        image = "alpine:3.24",
        command = listOf("sh", "-c", "trap 'echo flushed; exit 0' TERM; echo ready; while :; do sleep 1; done"),
        target = ContainerTarget.Worker(readiness = ReadinessStrategy.FixedDelay(1.milliseconds)),
        containerFactory = { container },
        launchConfigurationObserver = { _, _ -> }
      )
      try {
        aut.start(emptyList())
        aut.stop()
        finalLogs shouldContain "flushed"
      } finally {
        aut.stop()
      }
    }

    test("stalled Docker transport has a deadline and still attempts removal") {
      val release = java.util.concurrent.CountDownLatch(1)
      val entered = java.util.concurrent.CountDownLatch(1)
      val container = ShutdownContainer {
        entered.countDown()
        while (release.count > 0) {
          try {
            release.await()
          } catch (_: InterruptedException) { /* blocked transport */ }
        }
      }
      val aut = shutdownAut(container)
      try {
        aut.start(emptyList())
        val start = System.nanoTime()
        aut.stop()
        entered.count shouldBe 0
        (System.nanoTime() - start < 2_000_000_000) shouldBe true
        container.stopCalls shouldBe 1
        aut.stop()
        container.stopCalls shouldBe 1
      } finally {
        release.countDown()
      }
    }

    test("cancellation propagates after cleanup and failed cleanup can be retried") {
      val container = ShutdownContainer { throw kotlinx.coroutines.CancellationException("cancelled") }
      val aut = shutdownAut(container)
      aut.start(emptyList())
      shouldThrow<kotlinx.coroutines.CancellationException> { aut.stop() }
      container.stopCalls shouldBe 1
      aut.stop()
      container.stopCalls shouldBe 1

      val retryContainer = ShutdownContainer {}
      retryContainer.failCleanup = true
      val retryAut = shutdownAut(retryContainer)
      retryAut.start(emptyList())
      shouldThrow<IllegalStateException> { retryAut.stop() }
      retryContainer.stopCalls shouldBe 1
      retryContainer.failCleanup = false
      retryAut.stop()
      retryContainer.stopCalls shouldBe 2
    }

    test("caller timeout propagates even if fallback cleanup also fails") {
      val release = java.util.concurrent.CountDownLatch(1)
      val container = ShutdownContainer {
        while (release.count > 0) {
          try {
            release.await()
          } catch (_: InterruptedException) { /* blocked transport */ }
        }
      }
      container.failCleanup = true
      val aut = shutdownAut(container)
      try {
        aut.start(emptyList())
        shouldThrow<kotlinx.coroutines.TimeoutCancellationException> {
          kotlinx.coroutines.withTimeout(50.milliseconds) { aut.stop() }
        }
        container.stopCalls shouldBe 1
      } finally {
        release.countDown()
        container.failCleanup = false
        aut.stop()
      }
    }

    test("an application exceeding the grace period is removed") {
      val container = GenericContainer<Nothing>(DockerImageName.parse("alpine:3.24"))
      container.waitingFor(Wait.forLogMessage(".*ready.*", 1))
      val aut = ContainerApplicationUnderTest(
        image = "alpine:3.24",
        command = listOf("sh", "-c", "trap '' TERM; echo ready; while :; do sleep 1; done"),
        target = ContainerTarget.Worker(readiness = ReadinessStrategy.FixedDelay(1.milliseconds)),
        gracefulShutdownTimeout = 200.milliseconds,
        containerFactory = { container },
        launchConfigurationObserver = { _, _ -> }
      )
      try {
        aut.start(emptyList())
        val id = container.containerId
        val start = System.nanoTime()
        aut.stop()
        (System.nanoTime() - start < 7_000_000_000) shouldBe true
        shouldThrow<com.github.dockerjava.api.exception.NotFoundException> {
          container.dockerClient.inspectContainerCmd(id).exec()
        }
      } finally {
        aut.stop()
      }
    }

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

private fun shutdownAut(container: ShutdownContainer) = ContainerApplicationUnderTest(
  image = "busybox:latest",
  target = ContainerTarget.Worker(readiness = ReadinessStrategy.FixedDelay(1.milliseconds)),
  gracefulShutdownTimeout = 100.milliseconds,
  containerFactory = { container },
  launchConfigurationObserver = { _, _ -> }
)

private class ShutdownContainer(action: () -> Unit) : GenericContainer<ShutdownContainer>(DockerImageName.parse("busybox:latest")) {
  var stopCalls = 0
  var failCleanup = false
  private val command = java.lang.reflect.Proxy.newProxyInstance(
    javaClass.classLoader,
    arrayOf(com.github.dockerjava.api.command.StopContainerCmd::class.java)
  ) { proxy, method, _ ->
    when (method.name) {
      "exec" -> {
        action()
        null
      }

      "withTimeout" -> proxy

      else -> null
    }
  } as com.github.dockerjava.api.command.StopContainerCmd
  private val client = java.lang.reflect.Proxy.newProxyInstance(
    javaClass.classLoader,
    arrayOf(com.github.dockerjava.api.DockerClient::class.java)
  ) { _, method, _ -> if (method.name == "stopContainerCmd") command else null } as com.github.dockerjava.api.DockerClient
  override fun start() = Unit
  override fun getContainerId(): String = "fake"
  override fun getDockerClient(): com.github.dockerjava.api.DockerClient = client
  override fun stop() {
    stopCalls++
    if (failCleanup) error("cleanup failed")
  }
}
