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
import kotlinx.coroutines.withTimeoutOrNull
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
    val commandArgs = argsProvider.provide(configurationMap)
    val fullCommand = command + commandArgs
    val envVars = resolveEnv(configurationMap)
    launchConfigurationObserver(fullCommand, envVars)

    beforeStarted(configurationMap)

    val container = containerFactory()
    applyContainerConfiguration(container = container, fullCommand = fullCommand, envVars = envVars)
    logger.info("Starting container image {} with {} env vars", image, envVars.size)

    runCatching {
      withContext(Dispatchers.IO) {
        container.start()
      }
    }.fold(
      onSuccess = {},
      onFailure = { throwable ->
        val containerLogs = runCatching { container.logs }.getOrElse { "<container logs unavailable>" }
        throw IllegalStateException(
          "Failed to start container application `$image`. Logs:\n$containerLogs",
          throwable
        )
      }
    )
    withContext(Dispatchers.IO) {
      runCatching {
        container.followOutput(Slf4jLogConsumer(logger).withPrefix(image))
      }.onFailure {
        logger.debug("Container log streaming could not be attached: {}", it.message)
      }
    }

    runningContainer = Some(container)
    try {
      ReadinessChecker.check(target.readiness)
      logger.info("Container application is ready")
    } catch (t: IllegalStateException) {
      stop()
      throw t
    }

    return ContainerApplicationContext(container)
  }

  override suspend fun stop() {
    when (val activeContainer = runningContainer) {
      is Some -> {
        val container = activeContainer.value
        val gracefullyStopped = withContext(Dispatchers.IO) {
          withTimeoutOrNull(gracefulShutdownTimeout) {
            container.stop()
          } != null
        }

        if (!gracefullyStopped) {
          logger.warn("Container did not stop in time, force-closing")
          withContext(Dispatchers.IO) { container.close() }
        }
      }

      None -> Unit
    }
    runningContainer = None
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
    when (val target = target) {
      is ContainerTarget.Server -> {
        if (target.bindHostPort) {
          container.withExposedPorts(target.internalPort)
          container.withCreateContainerCmdModifier { command ->
            val hostConfig = command.hostConfig ?: HostConfig.newHostConfig()
            command.withHostConfig(
              hostConfig.withPortBindings(
                PortBinding(
                  Ports.Binding.bindPort(target.hostPort),
                  ExposedPort(target.internalPort)
                )
              )
            )
          }
        }
      }

      is ContainerTarget.Worker -> Unit
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
