@file:Suppress("UNCHECKED_CAST")

package com.trendyol.stove.testing.e2e

import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import io.ktor.server.application.*
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.*

/**
 *  Definition for Application Under Test for Ktor enabled application
 */
@StoveDsl
internal fun TestSystem.systemUnderTest(
  runner: Runner<Application>,
  withParameters: List<String> = listOf()
): ReadyTestSystem = applicationUnderTest(KtorApplicationUnderTest(this, runner, withParameters))

@StoveDsl
fun WithDsl.ktor(
  runner: Runner<Application>,
  withParameters: List<String> = listOf()
): ReadyTestSystem = this.testSystem.systemUnderTest(runner, withParameters)

@StoveDsl
class KtorApplicationUnderTest(
  private val testSystem: TestSystem,
  private val runner: Runner<Application>,
  private val parameters: List<String>
) : ApplicationUnderTest<Application> {
  private lateinit var application: Application

  override suspend fun start(configurations: List<String>): Application = coroutineScope {
    val allConfigurations = (configurations + defaultConfigurations() + parameters)
      .map { "--$it" }
      .distinct()
      .toTypedArray()
    application = runner(allConfigurations)
    testSystem.activeSystems
      .map { it.value }
      .filter { it is RunnableSystemWithContext<*> || it is AfterRunAwareWithContext<*> }
      .map { it as AfterRunAwareWithContext<Application> }
      .map { async { it.afterRun(application) } }
      .awaitAll()

    application
  }

  @OptIn(InternalAPI::class)
  override suspend fun stop(): Unit = application.disposeAndJoin()

  private fun defaultConfigurations(): Array<String> = arrayOf("test-system=true")
}
