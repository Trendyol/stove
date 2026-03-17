package com.trendyol.stove.recipes.quarkus.e2e.setup

import com.trendyol.stove.recipes.quarkus.StoveStartupSignal
import com.trendyol.stove.system.Runner
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.WithDsl
import com.trendyol.stove.system.abstractions.AfterRunAwareWithContext
import com.trendyol.stove.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.system.abstractions.ReadyStove
import com.trendyol.stove.system.annotations.StoveDsl
import io.quarkus.runtime.Quarkus
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicReference

/**
 * DSL function to configure Quarkus as the application under test.
 *
 * @param runner The function that starts the Quarkus application (typically `QuarkusMainApp.main`)
 * @param withParameters Additional configuration parameters to pass to Quarkus
 */
fun WithDsl.quarkus(
  runner: Runner<Unit>,
  withParameters: List<String> = listOf()
): ReadyStove = this.stove.systemUnderTest(runner, withParameters)

internal fun Stove.systemUnderTest(
  runner: Runner<Unit>,
  withParameters: List<String> = listOf()
): ReadyStove {
  this.applicationUnderTest(QuarkusAppUnderTest(this, runner, withParameters))
  return this
}

/**
 * Application under test implementation for Quarkus.
 *
 * Keeps the public `quarkus(runner, withParameters)` DSL intact and starts
 * Quarkus by invoking the provided `main` runner on a background thread.
 */
class QuarkusAppUnderTest(
  private val stove: Stove,
  private val runner: Runner<Unit>,
  private val parameters: List<String>
) : ApplicationUnderTest<Unit> {
  private var launcher: DirectQuarkusLauncher? = null

  override suspend fun start(configurations: List<String>): Unit = coroutineScope {
    val directLauncher = DirectQuarkusLauncher(runner, configurations + parameters)
    launcher = directLauncher
    directLauncher.start()

    try {
      waitForStartupSignal(directLauncher)
      notifySystemsAfterRun()
    } catch (error: Throwable) {
      directLauncher.stop()
      launcher = null
      throw error
    }
  }

  override suspend fun stop() {
    launcher?.stop()
    launcher = null
  }

  /**
   * Waits for the Quarkus application to publish its startup signal.
   */
  private suspend fun waitForStartupSignal(directLauncher: DirectQuarkusLauncher) {
    val startTime = System.currentTimeMillis()
    val timeout = STARTUP_TIMEOUT_MS

    while (true) {
      directLauncher.failureOrNull()?.let { failure ->
        throw IllegalStateException("Quarkus startup failed", failure)
      }

      if (directLauncher.isReady()) {
        println("[QuarkusAppUnderTest] Quarkus startup signal received")
        return
      }

      if (!directLauncher.isAlive()) {
        error("Quarkus launcher thread exited before the startup signal was received")
      }

      if (System.currentTimeMillis() - startTime > timeout) {
        error("Timeout waiting for Quarkus startup signal")
      }

      delay(POLL_INTERVAL_MS)
    }
  }

  /**
   * Notifies all registered systems that the application has started.
   */
  private suspend fun notifySystemsAfterRun() {
    stove.activeSystems
      .map { it.value }
      .filterIsInstance<AfterRunAwareWithContext<Unit>>()
      .forEach { it.afterRun(Unit) }
  }
}

private class DirectQuarkusLauncher(
  private val runner: Runner<Unit>,
  configurations: List<String>
) : Closeable {
  private val configurationProperties = parseConfigurations(configurations)
  private val runnerArguments = emptyArray<String>()
  private val previousSystemProperties = mutableMapOf<String, String?>()
  private val startupFailure = AtomicReference<Throwable?>(null)
  private var launcherThread: Thread? = null

  fun start() {
    check(launcherThread == null) { "Quarkus launcher already started" }

    clearStartupSignal()
    applyConfigurationProperties()

    try {
      val thread = Thread(
        {
          try {
            runner(runnerArguments)
          } catch (error: Throwable) {
            startupFailure.set(unwrap(error))
          }
        },
        "quarkus-main-launcher"
      )

      thread.isDaemon = false
      thread.setUncaughtExceptionHandler { _, error ->
        startupFailure.compareAndSet(null, unwrap(error))
      }

      launcherThread = thread
      thread.start()
    } catch (error: Throwable) {
      restoreConfigurationProperties()
      throw error
    }
  }

  fun isAlive(): Boolean = launcherThread?.isAlive == true

  fun failureOrNull(): Throwable? = startupFailure.get()

  fun isReady(): Boolean = System.getProperty(StoveStartupSignal.READY_PROPERTY) == READY_VALUE

  fun stop() {
    val thread = launcherThread
    var stopFailure: Throwable? = null

    try {
      if (thread != null && thread.isAlive) {
        try {
          requestShutdown()
        } catch (error: Throwable) {
          stopFailure = error
        }

        thread.join(SHUTDOWN_TIMEOUT_MS)
        if (thread.isAlive) {
          throw IllegalStateException("Timeout waiting for Quarkus to shut down")
        }
      }
    } finally {
      clearStartupSignal()
      restoreConfigurationProperties()
      launcherThread = null
    }

    stopFailure?.let { throw it }
  }

  override fun close() {
    stop()
  }

  private fun requestShutdown() {
    Quarkus.asyncExit()
  }

  private fun applyConfigurationProperties() {
    configurationProperties.forEach { (key, value) ->
      previousSystemProperties[key] = System.getProperty(key)
      System.setProperty(key, value)
    }
  }

  private fun clearStartupSignal() {
    System.clearProperty(StoveStartupSignal.READY_PROPERTY)
  }

  private fun restoreConfigurationProperties() {
    previousSystemProperties.forEach { (key, previousValue) ->
      if (previousValue == null) {
        System.clearProperty(key)
      } else {
        System.setProperty(key, previousValue)
      }
    }
    previousSystemProperties.clear()
  }
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

private fun unwrap(error: Throwable): Throwable = when (error) {
  is InvocationTargetException -> error.targetException ?: error
  else -> error.cause?.takeIf { error is RuntimeException && error.message == null } ?: error
}

private const val STARTUP_TIMEOUT_MS = 30_000L
private const val SHUTDOWN_TIMEOUT_MS = 10_000L
private const val POLL_INTERVAL_MS = 250L
private const val READY_VALUE = "true"
