@file:Suppress("UNCHECKED_CAST")

package com.trendyol.stove.ktor

import com.trendyol.stove.system.*
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl
import io.ktor.server.application.*
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.*

/**
 *  Definition for Application Under Test for Ktor enabled application
 */
internal fun Stove.systemUnderTest(
  runner: Runner<Application>,
  withParameters: List<String> = listOf()
): ReadyStove = applicationUnderTest(KtorApplicationUnderTest(this, runner, withParameters))

fun WithDsl.ktor(
  runner: Runner<Application>,
  withParameters: List<String> = listOf()
): ReadyStove = this.stove.systemUnderTest(runner, withParameters)

@StoveDsl
class KtorApplicationUnderTest(
  private val stove: Stove,
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
    stove.systemsOf<AfterRunAwareWithContext<Application>>()
      .map { async { it.afterRun(application) } }
      .awaitAll()

    application
  }

  @OptIn(InternalAPI::class)
  override suspend fun stop(): Unit = application.disposeAndJoin()

  private fun defaultConfigurations(): Array<String> = arrayOf("test-system=true")
}
