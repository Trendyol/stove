package com.trendyol.stove.recipes.quarkus.e2e.setup

import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import kotlinx.coroutines.*

/**
 * DSL function to configure Quarkus as the application under test.
 *
 * @param runner The function that starts the Quarkus application (typically `QuarkusMainApp.main`)
 * @param withParameters Additional configuration parameters to pass to Quarkus
 */
@StoveDsl
fun WithDsl.quarkus(
  runner: Runner<Unit>,
  withParameters: List<String> = listOf()
): ReadyTestSystem = this.testSystem.systemUnderTest(runner, withParameters)

@StoveDsl
internal fun TestSystem.systemUnderTest(
  runner: Runner<Unit>,
  withParameters: List<String> = listOf()
): ReadyTestSystem {
  this.applicationUnderTest(QuarkusAppUnderTest(this, runner, withParameters))
  return this
}

/**
 * Application under test implementation for Quarkus.
 *
 * Handles starting the Quarkus application, waiting for the Arc container to be ready,
 * and capturing the Quarkus classloader for bridge operations.
 */
class QuarkusAppUnderTest(
  private val testSystem: TestSystem,
  private val runner: Runner<Unit>,
  private val parameters: List<String>
) : ApplicationUnderTest<Unit> {

  override suspend fun start(configurations: List<String>): Unit = coroutineScope {
    configureQuarkusForTesting()

    val allConfigurations = (configurations + parameters).map { "-D$it" }.toTypedArray()

    // Start Quarkus in a background coroutine
    launch(Dispatchers.IO) {
      runner(allConfigurations)
    }

    // Wait for Arc container to be ready
    waitForArcContainer()

    // Notify other systems that the app is running
    notifySystemsAfterRun()
  }

  override suspend fun stop() {
    QuarkusContext.reset()
  }

  /**
   * Configures system properties for running Quarkus in test mode.
   */
  private fun configureQuarkusForTesting() {
    System.setProperty("quarkus.launch.dev-mode", "false")
    System.setProperty("quarkus.test.continuous-testing", "disabled")
  }

  /**
   * Waits for the Quarkus Arc container to become ready.
   * Polls the thread pool for a thread with the Quarkus classloader.
   */
  private suspend fun waitForArcContainer() {
    val startTime = System.currentTimeMillis()
    val timeout = 30_000L
    val pollInterval = 500L

    while (true) {
      try {
        if (ArcContainerAccessor.isContainerReady()) {
          println("[QuarkusAppUnderTest] Arc container is ready")
          return
        }
      } catch (e: Exception) {
        // Log progress after initial delay
        if (System.currentTimeMillis() - startTime > 5000) {
          println("[QuarkusAppUnderTest] Still waiting... ${e.message}")
        }
      }

      if (System.currentTimeMillis() - startTime > timeout) {
        error("Timeout waiting for Quarkus Arc container to start")
      }

      delay(pollInterval)
    }
  }

  /**
   * Notifies all registered systems that the application has started.
   */
  private suspend fun notifySystemsAfterRun() {
    testSystem.activeSystems
      .map { it.value }
      .filterIsInstance<AfterRunAwareWithContext<Unit>>()
      .forEach { it.afterRun(Unit) }
  }
}
