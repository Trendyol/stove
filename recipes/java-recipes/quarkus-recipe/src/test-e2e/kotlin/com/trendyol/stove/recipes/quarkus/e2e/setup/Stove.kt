package com.trendyol.stove.recipes.quarkus.e2e.setup

import com.trendyol.stove.recipes.quarkus.QuarkusMainApp
import com.trendyol.stove.testing.e2e.http.*
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import io.kotest.core.config.AbstractProjectConfig
import jakarta.enterprise.inject.spi.BeanManager
import kotlinx.coroutines.*
import kotlin.reflect.KClass

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
            QuarkusMainApp.main(params)
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

@StoveDsl
@Suppress("UNCHECKED_CAST")
class QuarkusBridgeSystem(
  override val testSystem: TestSystem
) : BridgeSystem<BeanManager>(testSystem),
  PluggedSystem,
  AfterRunAwareWithContext<BeanManager> {
  override fun <D : Any> get(klass: KClass<D>): D {
    val bean = ctx.getBeans(klass.java).singleOrNull() ?: error("No bean found for $klass")
    val contextOfBean = ctx.createCreationalContext(bean)
    return ctx.getReference(bean, klass.java, contextOfBean) as D
  }
}

/**
 * Returns the bridge system associated with the test system.
 *
 * @receiver the test system.
 * @return the bridge system.
 * @throws SystemNotRegisteredException if the bridge system is not registered.
 */
@StoveDsl
fun WithDsl.bridge(): TestSystem = this.testSystem.withBridgeSystem(QuarkusBridgeSystem(this.testSystem))

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
