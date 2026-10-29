package com.trendyol.stove.container

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import com.trendyol.stove.containers.DEFAULT_REGISTRY
import com.trendyol.stove.containers.withProvidedRegistry
import com.trendyol.stove.system.ReadinessChecker
import com.trendyol.stove.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.system.application.ArgsProvider
import com.trendyol.stove.system.application.EnvProvider
import com.trendyol.stove.system.application.toConfigurationMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.output.Slf4jLogConsumer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal typealias ContainerFactory = () -> GenericContainer<*>
internal typealias LaunchConfigurationObserver = (List<String>, Map<String, String>) -> Unit

internal class ContainerApplicationUnderTest(
  private val image: String,
  private val target: ContainerTarget,
  private val command: List<String> = emptyList(),
  private val envProvider: EnvProvider = EnvProvider.empty(),
  private val argsProvider: ArgsProvider = ArgsProvider.empty(),
  private val beforeStarted: suspend (configurations: Map<String, String>) -> Unit = {},
  private val configureContainer: GenericContainer<*>.() -> Unit = {},
  private val gracefulShutdownTimeout: Duration = 5.seconds,
  private val containerFactory: ContainerFactory,
  private val launchConfigurationObserver: LaunchConfigurationObserver
) : ApplicationUnderTest<ContainerApplicationContext> {
  constructor(
    image: String,
    target: ContainerTarget,
    registry: String = DEFAULT_REGISTRY,
    compatibleSubstitute: String? = null,
    command: List<String> = emptyList(),
    envProvider: EnvProvider = EnvProvider.empty(),
    argsProvider: ArgsProvider = ArgsProvider.empty(),
    beforeStarted: suspend (configurations: Map<String, String>) -> Unit = {},
    configureContainer: GenericContainer<*>.() -> Unit = {},
    gracefulShutdownTimeout: Duration = 5.seconds
  ) : this(
    image = image,
    target = target,
    command = command,
    envProvider = envProvider,
    argsProvider = argsProvider,
    beforeStarted = beforeStarted,
    configureContainer = configureContainer,
    gracefulShutdownTimeout = gracefulShutdownTimeout,
    containerFactory = {
      defaultContainerFactory(
        image = image,
        registry = registry,
        compatibleSubstitute = compatibleSubstitute
      )
    },
    launchConfigurationObserver = { _, _ -> }
  )

  private val logger = LoggerFactory.getLogger(javaClass)
  private var runningContainer: Option<GenericContainer<*>> = None

  override suspend fun start(configurations: List<String>): ContainerApplicationContext {
    val configurationMap = configurations.toConfigurationMap()
    val fullCommand = command + argsProvider.provide(configurationMap)
    val envVars = resolveEnv(configurationMap)
    launchConfigurationObserver(fullCommand, envVars)
    beforeStarted(configurationMap)

    val container = containerFactory()
    applyContainerConfiguration(container, fullCommand, envVars)
    logger.info("Starting container image {} with {} env vars", image, envVars.size)
    startContainer(container)
    attachContainerLogs(container)

    runningContainer = Some(container)
    awaitReadiness()
    return ContainerApplicationContext(container)
  }

  private suspend fun startContainer(container: GenericContainer<*>) {
    try {
      withContext(Dispatchers.IO) { container.start() }
    } catch (failure: Throwable) {
      val logs = runCatching { container.logs }.getOrElse { "<container logs unavailable>" }
      throw IllegalStateException("Failed to start container application `$image`. Logs:\n$logs", failure)
    }
  }

  private suspend fun attachContainerLogs(container: GenericContainer<*>): Unit = withContext(Dispatchers.IO) {
    runCatching {
      container.followOutput(Slf4jLogConsumer(logger).withPrefix(image))
    }.onFailure {
      logger.debug("Container log streaming could not be attached: {}", it.message)
    }
  }

  private suspend fun awaitReadiness() {
    try {
      ReadinessChecker.check(target.readiness)
      logger.info("Container application is ready")
    } catch (failure: IllegalStateException) {
      stop()
      throw failure
    }
  }

  override suspend fun stop() {
    val container = runningContainer.getOrNull() ?: return
    try {
      withContext(Dispatchers.IO) {
        // GenericContainer.stop() removes the container without an application
        // shutdown grace period. Ask Docker to signal the app and wait first.
        container.containerId?.let { id ->
          runCatching {
            val seconds = kotlin.math.ceil(gracefulShutdownTimeout.inWholeMilliseconds / 1000.0)
              .toInt().coerceAtLeast(1)
            container.dockerClient.stopContainerCmd(id).withTimeout(seconds).exec()
          }.onFailure {
            logger.warn("Graceful container shutdown failed; removing container", it)
          }
        }
        container.stop()
      }
    } finally {
      runningContainer = None
    }
  }

  private fun resolveEnv(configurationMap: Map<String, String>): Map<String, String> {
    val mappedEnv = envProvider.provide(configurationMap)
    return when (val target = target) {
      is ContainerTarget.Server -> mappedEnv + (target.portEnvVar to target.internalPort.toString())
      is ContainerTarget.Worker -> mappedEnv
    }
  }

  private fun applyContainerConfiguration(
    container: GenericContainer<*>,
    fullCommand: List<String>,
    envVars: Map<String, String>
  ) {
    container
      .withEnv(envVars)
    configureTarget(container)
    if (fullCommand.isNotEmpty()) {
      container.withCommand(*fullCommand.toTypedArray())
    }
    configureContainer(container)
  }

  private fun configureTarget(container: GenericContainer<*>) {
    val server = target as? ContainerTarget.Server ?: return
    if (!server.bindHostPort) return

    container.withExposedPorts(server.internalPort)
    container.withCreateContainerCmdModifier { command ->
      val hostConfig = command.hostConfig ?: HostConfig.newHostConfig()
      command.withHostConfig(
        hostConfig.withPortBindings(
          PortBinding(Ports.Binding.bindPort(server.hostPort), ExposedPort(server.internalPort))
        )
      )
    }
  }

  companion object {
    private fun defaultContainerFactory(
      image: String,
      registry: String,
      compatibleSubstitute: String?
    ): GenericContainer<*> =
      withProvidedRegistry(
        imageName = image,
        registry = registry,
        compatibleSubstitute = compatibleSubstitute
      ) { imageName ->
        GenericContainer(imageName)
      }
  }
}
