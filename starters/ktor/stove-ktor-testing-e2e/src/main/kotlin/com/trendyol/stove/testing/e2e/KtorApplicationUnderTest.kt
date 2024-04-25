@file:Suppress("UNCHECKED_CAST")

package com.trendyol.stove.testing.e2e

import com.trendyol.stove.testing.e2e.system.Runner
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.WithDsl
import com.trendyol.stove.testing.e2e.system.abstractions.AfterRunAwareWithContext
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.testing.e2e.system.abstractions.ReadyTestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.RunnableSystemWithContext
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import io.ktor.server.application.*
import io.ktor.server.engine.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
    val allConfigurations = (configurations + defaultConfigurations() + parameters).toTypedArray()
    application = runner(allConfigurations)
    testSystem.activeSystems
      .map { it.value }
      .filter { it is RunnableSystemWithContext<*> || it is AfterRunAwareWithContext<*> }
      .map { it as AfterRunAwareWithContext<Application> }
      .map { async { it.afterRun(application) } }
      .awaitAll()

    application
  }

  override suspend fun stop(): Unit = application.dispose()

  private fun defaultConfigurations(): Array<String> = arrayOf("test-system=true")
}
