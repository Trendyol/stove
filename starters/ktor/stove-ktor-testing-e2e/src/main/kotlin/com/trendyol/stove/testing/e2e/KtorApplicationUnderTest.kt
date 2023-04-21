@file:Suppress("UNCHECKED_CAST")

package com.trendyol.stove.testing.e2e

import com.trendyol.stove.testing.e2e.system.Runner
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.WithDsl
import com.trendyol.stove.testing.e2e.system.abstractions.*
import io.ktor.server.engine.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 *  Definition for Application Under Test for Ktor enabled application
 */
fun TestSystem.systemUnderTest(
    runner: Runner<ApplicationEngine>,
    withParameters: List<String> = listOf(),
): ReadyTestSystem = applicationUnderTest(KtorApplicationUnderTest(this, runner, withParameters))

@ExperimentalStoveDsl
fun WithDsl.ktor(
    runner: Runner<ApplicationEngine>,
    withParameters: List<String> = listOf(),
): ReadyTestSystem = this.testSystem.systemUnderTest(runner, withParameters)

class KtorApplicationUnderTest(
    private val testSystem: TestSystem,
    private val runner: Runner<ApplicationEngine>,
    private val parameters: List<String>,
) : ApplicationUnderTest<ApplicationEngine> {
    private lateinit var application: ApplicationEngine

    override suspend fun start(configurations: List<String>): ApplicationEngine = coroutineScope {
        val allConfigurations = (configurations + defaultConfigurations() + parameters).toTypedArray()
        application = runner(allConfigurations)
        testSystem.activeSystems
            .map { it.value }
            .filter { it is RunnableSystemWithContext<*> || it is AfterRunAwareWithContext<*> }
            .map { it as RunnableSystemWithContext<ApplicationEngine> }
            .map { async { it.afterRun(application) } }
            .awaitAll()

        application
    }

    override suspend fun stop(): Unit = application.stop()

    private fun defaultConfigurations(): Array<String> = arrayOf("test-system=true")
}
