@file:Suppress("UNCHECKED_CAST")

package com.trendyol.stove.testing.e2e

import com.trendyol.stove.testing.e2e.system.Runner
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.WithDsl
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import kotlinx.coroutines.*
import org.springframework.context.ConfigurableApplicationContext

@StoveDsl
internal fun TestSystem.systemUnderTest(
  runner: Runner<ConfigurableApplicationContext>,
  withParameters: List<String> = listOf()
): ReadyTestSystem {
  this.applicationUnderTest(SpringApplicationUnderTest(this, runner, withParameters))
  return this
}

@StoveDsl
fun WithDsl.springBoot(
  runner: Runner<ConfigurableApplicationContext>,
  withParameters: List<String> = listOf()
): ReadyTestSystem {
  SpringBootVersionCheck.ensureSpringBootAvailable()
  return this.testSystem.systemUnderTest(runner, withParameters)
}

@StoveDsl
class SpringApplicationUnderTest(
  private val testSystem: TestSystem,
  private val runner: Runner<ConfigurableApplicationContext>,
  private val parameters: List<String>
) : ApplicationUnderTest<ConfigurableApplicationContext> {
  private lateinit var application: ConfigurableApplicationContext

  companion object {
    private const val DELAY = 500L
  }

  override suspend fun start(configurations: List<String>): ConfigurableApplicationContext =
    coroutineScope {
      val allConfigurations = (configurations + defaultConfigurations() + parameters).map { "--$it" }.toTypedArray()
      application = runner(allConfigurations)
      while (!application.isRunning || !application.isActive) {
        delay(DELAY)
        continue
      }
      testSystem.activeSystems
        .map { it.value }
        .filter { it is RunnableSystemWithContext<*> || it is AfterRunAwareWithContext<*> }
        .map { it as AfterRunAwareWithContext<ConfigurableApplicationContext> }
        .map { async(context = Dispatchers.IO) { it.afterRun(application) } }
        .awaitAll()
      application
    }

  override suspend fun stop(): Unit = application.stop()

  private fun defaultConfigurations(): Array<String> = arrayOf("test-system=true")
}
