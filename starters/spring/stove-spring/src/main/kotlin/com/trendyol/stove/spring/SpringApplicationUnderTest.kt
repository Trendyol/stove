@file:Suppress("UNCHECKED_CAST")

package com.trendyol.stove.spring

import com.trendyol.stove.system.Runner
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.WithDsl
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl
import kotlinx.coroutines.*
import org.springframework.context.ConfigurableApplicationContext

@StoveDsl
internal fun Stove.systemUnderTest(
  runner: Runner<ConfigurableApplicationContext>,
  withParameters: List<String> = listOf()
): ReadyStove {
  this.applicationUnderTest(SpringApplicationUnderTest(this, runner, withParameters))
  return this
}

@StoveDsl
fun WithDsl.springBoot(
  runner: Runner<ConfigurableApplicationContext>,
  withParameters: List<String> = listOf()
): ReadyStove {
  SpringBootVersionCheck.ensureSpringBootAvailable()
  return this.stove.systemUnderTest(runner, withParameters)
}

@StoveDsl
class SpringApplicationUnderTest(
  private val stove: Stove,
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
      stove.activeSystems
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
