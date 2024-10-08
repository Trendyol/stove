package com.trendyol.stove.recipes.quarkus.e2e.setup

import com.trendyol.stove.recipes.quarkus.QuarkusRecipeApp
import com.trendyol.stove.testing.e2e.http.*
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import io.kotest.core.config.AbstractProjectConfig
import kotlinx.coroutines.*

class Stove : AbstractProjectConfig() {
  override suspend fun beforeProject() {
    TestSystem()
      .with {
        httpClient {
          HttpClientSystemOptions(
            baseUrl = "http://localhost:8080"
          )
        }
        quarkus(
          runner = { params ->
            QuarkusRecipeApp.main(params)
          }
        )
      }.run()
  }

  override suspend fun afterProject() {
    TestSystem.stop()
  }
}

@StoveDsl
internal fun TestSystem.systemUnderTest(
  runner: Runner<Unit>,
  withParameters: List<String> = listOf()
): ReadyTestSystem {
  this.applicationUnderTest(QuarkusAppUnderTest(this, runner, withParameters))
  return this
}

@StoveDsl
fun WithDsl.quarkus(
  runner: Runner<Unit>,
  withParameters: List<String> = listOf()
): ReadyTestSystem = this.testSystem.systemUnderTest(runner, withParameters)

@Suppress("UNCHECKED_CAST")
class QuarkusAppUnderTest(
  private val testSystem: TestSystem,
  private val runner: Runner<Unit>,
  private val parameters: List<String>
) : ApplicationUnderTest<Unit> {
  override suspend fun start(configurations: List<String>): Unit = coroutineScope {
    val allConfigurations = (configurations + parameters).map { "--$it" }.toTypedArray()
    runner(allConfigurations)
    testSystem.activeSystems
      .map { it.value }
      .filter { it is RunnableSystemWithContext<*> || it is AfterRunAwareWithContext<*> }
      .map { it as AfterRunAwareWithContext<Unit> }
      .map { async(context = Dispatchers.IO) { it.afterRun(Unit) } }
      .awaitAll()
  }

  override suspend fun stop(): Unit = Unit
}
