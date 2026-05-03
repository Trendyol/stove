package com.trendyol.stove.container

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import com.trendyol.stove.containers.withProvidedRegistry
import com.trendyol.stove.system.ReadinessChecker
import com.trendyol.stove.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.system.application.toConfigurationMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.output.Slf4jLogConsumer

internal typealias ContainerFactory = (ContainerApplicationOptions) -> GenericContainer<*>
internal typealias LaunchConfigurationObserver = (List<String>, Map<String, String>) -> Unit

class ContainerApplicationUnderTest internal constructor(
  private val options: ContainerApplicationOptions,
  private val containerFactory: ContainerFactory,
  private val launchConfigurationObserver: LaunchConfigurationObserver
) : ApplicationUnderTest<ContainerApplicationContext> {
  constructor(options: ContainerApplicationOptions) : this(
    options = options,
    containerFactory = ::defaultContainerFactory,
    launchConfigurationObserver = { _, _ -> }
  )

  private val logger = LoggerFactory.getLogger(javaClass)
  private var runningContainer: Option<GenericContainer<*>> = None

  override suspend fun start(configurations: List<String>): ContainerApplicationContext {
    val configurationMap = configurations.toConfigurationMap()
    val commandArgs = options.argsProvider.provide(configurationMap)
    val fullCommand = options.command + commandArgs
    val envVars = resolveEnv(configurationMap)
    launchConfigurationObserver(fullCommand, envVars)

    options.beforeStarted(configurationMap, options)

    val container = containerFactory(options)
    configureContainer(container = container, fullCommand = fullCommand, envVars = envVars)
    logger.info("Starting container image {} with {} env vars", options.image, envVars.size)

    runCatching {
      withContext(Dispatchers.IO) {
        container.start()
      }
    }.fold(
      onSuccess = {},
      onFailure = { throwable ->
        val containerLogs = runCatching { container.logs }.getOrElse { "<container logs unavailable>" }
        throw IllegalStateException(
          "Failed to start container application `${options.image}`. Logs:\n$containerLogs",
          throwable
        )
      }
    )
    withContext(Dispatchers.IO) {
      runCatching {
        container.followOutput(Slf4jLogConsumer(logger).withPrefix(options.image))
      }.onFailure {
        logger.debug("Container log streaming could not be attached: {}", it.message)
      }
    }

    runningContainer = Some(container)
    try {
      ReadinessChecker.check(options.target.readiness)
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
          withTimeoutOrNull(options.gracefulShutdownTimeout) {
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
    val mappedEnv = options.envProvider.provide(configurationMap)
    return when (val target = options.target) {
      is ContainerTarget.Server -> mappedEnv + (target.portEnvVar to target.internalPort.toString())
      is ContainerTarget.Worker -> mappedEnv
    }
  }

  private fun configureContainer(
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
    options.configureContainer(container)
  }

  private fun configureTarget(container: GenericContainer<*>) {
    when (val target = options.target) {
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
    private fun defaultContainerFactory(options: ContainerApplicationOptions): GenericContainer<*> =
      withProvidedRegistry(
        imageName = options.image,
        registry = options.registry,
        compatibleSubstitute = options.compatibleSubstitute
      ) { imageName ->
        GenericContainer(imageName)
      }
  }
}
