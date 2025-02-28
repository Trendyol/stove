package com.trendyol.stove.recipes.quarkus.e2e.setup

import com.trendyol.stove.recipes.quarkus.*
import com.trendyol.stove.testing.e2e.http.*
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import io.kotest.core.config.AbstractProjectConfig
import kotlinx.coroutines.*
import java.util.concurrent.*
import kotlin.reflect.KClass

val singleThreadExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
  Thread(r).apply { name = "CustomCoroutineThread" }
}

val dispatcher = singleThreadExecutor.asCoroutineDispatcher()

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
            QuarkusMainApp.main(params)
          },
          withParameters = listOf(
            "quarkus.live-reload.enabled=false"
          )
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
class QuarkusBridgeSystem(
  override val testSystem: TestSystem
) : BridgeSystem<Thread>(testSystem) {
  override fun <D : Any> get(klass: KClass<D>): D {
    val found = StoveQuarkusBridge.resolveWithReflection(klass)
    return found
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
  override suspend fun start(configurations: List<String>): Unit = withContext(dispatcher) {
    val allConfigurations = (configurations + parameters).map { "--$it" }.toTypedArray()
    runner(allConfigurations)
    testSystem.activeSystems
      .map { it.value }
      .filter { it is RunnableSystemWithContext<*> || it is AfterRunAwareWithContext<*> }
      .map { it as AfterRunAwareWithContext<Unit> }
      .map { async(context = dispatcher) { it.afterRun(Unit) } }
      .awaitAll()
  }

  override suspend fun stop(): Unit = Unit
}
