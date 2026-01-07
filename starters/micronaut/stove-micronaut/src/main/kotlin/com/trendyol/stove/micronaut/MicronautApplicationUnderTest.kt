package com.trendyol.stove.micronaut

import com.trendyol.stove.system.*
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl
import io.micronaut.context.*
import kotlinx.coroutines.*

@StoveDsl
internal fun Stove.systemUnderTest(
  runner: Runner<ApplicationContext>,
  withParameters: List<String> = listOf()
): ReadyStove {
  this.applicationUnderTest(MicronautApplicationUnderTest(this, runner, withParameters))
  return this
}

@StoveDsl
fun WithDsl.micronaut(
  runner: Runner<ApplicationContext>,
  withParameters: List<String> = listOf()
): ReadyStove = this.stove.systemUnderTest(runner, withParameters)

@StoveDsl
class MicronautApplicationUnderTest(
  private val stove: Stove,
  private val runner: Runner<ApplicationContext>,
  private val parameters: List<String>
) : ApplicationUnderTest<ApplicationContext> {
  private lateinit var application: ApplicationContext

  companion object {
    private const val DELAY = 500L
  }

  override suspend fun start(configurations: List<String>): ApplicationContext = coroutineScope {
    val allConfigurations = (configurations + defaultConfigurations() + parameters).map { "--$it" }.toTypedArray()
    application = runner(allConfigurations)
    while (!application.isRunning) {
      delay(DELAY)
      continue
    }
    stove.activeSystems
      .map { it.value }
      .filterIsInstance<AfterRunAwareWithContext<ApplicationContext>>()
      .map { async(context = Dispatchers.IO) { it.afterRun(application) } }
      .awaitAll()
    application
  }

  override suspend fun stop() {
    application.stop()
  }

  private fun defaultConfigurations(): Array<String> = arrayOf("test-system=true")
}
