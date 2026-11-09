package com.trendyol.stove.quarkus

import kotlinx.coroutines.delay
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

internal class QuarkusLauncher(
  private val runtime: QuarkusLaunchRuntime,
  configurations: List<String>
) {
  private val configuration = parseConfigurations(configurations)
  private val runnerArguments = emptyArray<String>()
  private val startupFailure = AtomicReference<Throwable?>(null)
  private var previousSystemProperties: Map<String, String?> = emptyMap()
  private var launcherThread: Thread? = null

  @Suppress("TooGenericExceptionCaught")
  suspend fun start() {
    check(launcherThread == null) { "Quarkus launcher already started" }

    prepareStart()

    try {
      val thread = createLauncherThread()
      launcherThread = thread
      thread.start()
    } catch (error: Throwable) {
      cleanupAfterFailedStart()
      throw error
    }
    waitForApplication()
  }

  private fun isAlive(): Boolean = launcherThread?.isAlive == true

  private fun failureOrNull(): Throwable? = startupFailure.get()

  private fun isReady(): Boolean = runtime.isStarted()

  private fun describeReadinessState(): String =
    "launcherMode=${runtime.modeName}, " +
      "applicationStarted=${isReady()}, " +
      "launcherAlive=${isAlive()}, " +
      "launcherState=${launcherThread?.state ?: "not-started"}"

  @Suppress("TooGenericExceptionCaught")
  fun stop() {
    val thread = launcherThread
    var stopFailure: Throwable? = null

    try {
      stopFailure = stopRunningThread(thread)
    } finally {
      cleanup()
    }

    stopFailure?.let { throw it }
  }

  private fun prepareStart() {
    previousSystemProperties = applySystemProperties(configuration)
  }

  private fun createLauncherThread() = Thread(
    { runtime.launch(runnerArguments) },
    "quarkus-main-launcher"
  ).apply {
    isDaemon = false
    setUncaughtExceptionHandler { _, error ->
      startupFailure.compareAndSet(null, unwrap(error))
    }
  }

  private fun cleanupAfterFailedStart() {
    restoreSystemProperties(previousSystemProperties)
    previousSystemProperties = emptyMap()
  }

  @Suppress("TooGenericExceptionCaught")
  private fun stopRunningThread(thread: Thread?): Throwable? {
    if (thread == null || !thread.isAlive) return null

    val stopFailure = try {
      runtime.stop()
      null
    } catch (error: Throwable) {
      error
    }

    thread.join(SHUTDOWN_TIMEOUT_MS)
    if (thread.isAlive) {
      error("Timeout waiting for Quarkus to shut down")
    }
    return stopFailure
  }

  private fun cleanup() {
    restoreSystemProperties(previousSystemProperties)
    previousSystemProperties = emptyMap()
    runtime.close()
    launcherThread = null
  }

  private suspend fun waitForApplication() {
    val startTime = System.currentTimeMillis()
    val startupTimeoutMs = resolveTimeoutMillis(
      propertyName = STARTUP_TIMEOUT_PROPERTY,
      defaultValue = DEFAULT_STARTUP_TIMEOUT_MS
    )

    while (true) {
      failIfStartupFailed()
      if (isReady()) return
      if (!isAlive()) {
        failIfStartupFailed()
        error("Quarkus launcher exited before application startup completed")
      }
      failIfStartupTimedOut(startTime, startupTimeoutMs)

      delay(POLL_INTERVAL_MS.milliseconds)
    }
  }

  private fun failIfStartupFailed() {
    failureOrNull()?.let { failure ->
      throw IllegalStateException("Quarkus startup failed", failure)
    }
  }

  private fun failIfStartupTimedOut(
    startTime: Long,
    startupTimeoutMs: Long
  ) {
    if (System.currentTimeMillis() - startTime <= startupTimeoutMs) return
    error(readinessTimeoutMessage(startupTimeoutMs))
  }

  private fun readinessTimeoutMessage(startupTimeoutMs: Long) =
    "Timeout waiting for Quarkus application readiness after ${startupTimeoutMs}ms. " +
      describeReadinessState()
}

private fun parseConfigurations(configurations: List<String>): Map<String, String> {
  val properties = linkedMapOf<String, String>()
  configurations.forEach { configuration ->
    val separatorIndex = configuration.indexOf('=')
    require(separatorIndex > 0) {
      "Invalid Quarkus configuration '$configuration'. Expected key=value."
    }
    val key = configuration.substring(0, separatorIndex)
    val value = configuration.substring(separatorIndex + 1)
    properties[key] = value
  }
  return properties
}

private fun applySystemProperties(properties: Map<String, String>): Map<String, String?> =
  buildMap {
    properties.forEach { (key, value) ->
      put(key, System.getProperty(key))
      System.setProperty(key, value)
    }
  }

private fun restoreSystemProperties(previousSystemProperties: Map<String, String?>) {
  previousSystemProperties.forEach { (key, previousValue) ->
    if (previousValue == null) {
      System.clearProperty(key)
    } else {
      System.setProperty(key, previousValue)
    }
  }
}

private fun resolveTimeoutMillis(propertyName: String, defaultValue: Long): Long =
  System
    .getProperty(propertyName)
    ?.toLongOrNull()
    ?.takeIf { it > 0 }
    ?: defaultValue

private fun unwrap(error: Throwable): Throwable = when (error) {
  is InvocationTargetException -> error.targetException ?: error
  else -> error.cause?.takeIf { error is RuntimeException && error.message == null } ?: error
}

private const val DEFAULT_STARTUP_TIMEOUT_MS = 120_000L
private const val SHUTDOWN_TIMEOUT_MS = 10_000L
private const val POLL_INTERVAL_MS = 250L
private const val STARTUP_TIMEOUT_PROPERTY = "stove.quarkus.startup.timeout.ms"
