package com.trendyol.stove.recipes.quarkus.e2e.setup

import com.trendyol.stove.recipes.quarkus.QuarkusRecipeApp
import com.trendyol.stove.testing.e2e.bridge
import com.trendyol.stove.testing.e2e.http.*
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import io.kotest.core.config.AbstractProjectConfig
import jakarta.enterprise.inject.spi.BeanManager
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
        bridge()
        quarkus(
          runner = { params ->
            QuarkusRecipeApp.run(params, "e2eTest")
          },
          withParameters = listOf()
        )
      }.run()
  }

  override suspend fun afterProject() {
    TestSystem.stop()
  }
}

@StoveDsl
internal fun TestSystem.systemUnderTest(
  runner: Runner<BeanManager>,
  withParameters: List<String> = listOf()
): ReadyTestSystem {
  this.applicationUnderTest(QuarkusAppUnderTest(this, runner, withParameters))
  return this
}

@StoveDsl
fun WithDsl.quarkus(
  runner: Runner<BeanManager>,
  withParameters: List<String> = listOf()
): ReadyTestSystem = this.testSystem.systemUnderTest(runner, withParameters)

@Suppress("UNCHECKED_CAST")
class QuarkusAppUnderTest(
  private val testSystem: TestSystem,
  private val runner: Runner<BeanManager>,
  private val parameters: List<String>
) : ApplicationUnderTest<BeanManager> {
  override suspend fun start(configurations: List<String>): BeanManager = coroutineScope {
    val allConfigurations = (configurations + parameters).map { "--$it" }.toTypedArray()
    val di = runner(allConfigurations)
    testSystem.activeSystems
      .map { it.value }
      .filter { it is RunnableSystemWithContext<*> || it is AfterRunAwareWithContext<*> }
      .map { it as AfterRunAwareWithContext<BeanManager> }
      .map { async(context = Dispatchers.IO) { it.afterRun(di) } }
      .awaitAll()
    di
  }

  override suspend fun stop(): Unit = Unit
}
