package com.trendyol.stove.process

import com.trendyol.stove.system.ReadinessChecker
import com.trendyol.stove.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.system.annotations.StoveDsl
import com.trendyol.stove.system.application.toConfigurationMap
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * An [ApplicationUnderTest] that manages an OS process (any language/runtime).
 *
 * Lifecycle:
 * 1. [start]: Parses Stove configurations, builds environment variables via [EnvProvider]
 *    and CLI arguments via [ArgsProvider], starts the process, reads its output, and waits for readiness.
 * 2. [stop]: Sends SIGTERM, waits for graceful shutdown, force-kills if needed.
 *
 * ## Example
 *
 * ```kotlin
 * processApp {
 *     ProcessApplicationOptions(
 *         command = listOf("/path/to/server"),
 *         target = ProcessTarget.Server(port = 8080),
 *         envProvider = envMapper { "database.host" to "DB_HOST" }
 *     )
 * }
 * ```
 *
 * @see ProcessApplicationOptions
 * @see ProcessTarget
 * @see EnvProvider
 * @see ArgsProvider
 */
@StoveDsl
class ProcessApplicationUnderTest(
  private val options: ProcessApplicationOptions
) : ApplicationUnderTest<Unit> {
  private val logger = LoggerFactory.getLogger(javaClass)
  private var process: Process? = null

  override suspend fun start(configurations: List<String>) {
    val configMap = configurations.toConfigurationMap()
    val envVars = options.envProvider.provide(configMap)
    val cliArgs = options.argsProvider.provide(configMap)
    val fullCommand = options.command + cliArgs
    val processBuilder = createProcessBuilder(fullCommand, envVars)

    options.beforeStarted(configMap, options)

    logger.info("Starting process: {} with {} env vars and {} cli args", fullCommand, envVars.size, cliArgs.size)
    val startedProcess = withContext(Dispatchers.IO) { processBuilder.start() }
    process = startedProcess
    launchOutputReader(startedProcess)

    ReadinessChecker.check(options.target.readiness)
    logger.info("Process is ready")
  }

  private fun createProcessBuilder(command: List<String>, envVars: Map<String, String>): ProcessBuilder =
    ProcessBuilder(command).apply {
      redirectErrorStream(options.redirectErrorStream)
      options.workingDirectory?.let(::directory)
      environment().putAll(envVars)
      (options.target as? ProcessTarget.Server)?.let { target ->
        environment()[target.portEnvVar] = target.port.toString()
      }
    }

  override suspend fun stop() {
    val runningProcess = process ?: return
    logger.info("Stopping process (SIGTERM)")
    runningProcess.destroy()
    if (!runningProcess.waitFor(options.gracefulShutdownTimeout.inWholeSeconds, TimeUnit.SECONDS)) {
      logger.warn("Process did not stop gracefully, force-killing")
      runningProcess.destroyForcibly().waitFor()
    }
    logger.info("Process stopped (exit code: {})", runningProcess.exitValue())
  }

  private fun launchOutputReader(process: Process) {
    val commandName = options.command.firstOrNull()
      ?.substringAfterLast('/')
      ?.substringAfterLast('\\')
      ?: "process"

    Thread {
      process.inputStream.bufferedReader().forEachLine { line ->
        logger.info("[{}] {}", commandName, line)
      }
    }.apply {
      isDaemon = true
      name = "$commandName-output-reader"
      start()
    }
  }
}
