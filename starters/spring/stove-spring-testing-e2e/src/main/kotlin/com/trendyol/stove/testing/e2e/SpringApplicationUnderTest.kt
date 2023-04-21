@file:Suppress("UNCHECKED_CAST")

package com.trendyol.stove.testing.e2e

import com.trendyol.stove.testing.e2e.system.Runner
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.WithDsl
import com.trendyol.stove.testing.e2e.system.abstractions.*
import kotlinx.coroutines.*
import org.springframework.context.ConfigurableApplicationContext

@ExperimentalDsl
fun TestSystem.systemUnderTest(
    runner: Runner<ConfigurableApplicationContext>,
    withParameters: List<String> = listOf(),
): ReadyTestSystem {
    this.applicationUnderTest(SpringApplicationUnderTest(this, runner, withParameters))
    return this
}

@ExperimentalDsl
fun WithDsl.springBoot(
    runner: Runner<ConfigurableApplicationContext>,
    withParameters: List<String> = listOf(),
): ReadyTestSystem = this.testSystem.systemUnderTest(runner, withParameters)

class SpringApplicationUnderTest(
    private val testSystem: TestSystem,
    private val runner: Runner<ConfigurableApplicationContext>,
    private val parameters: List<String>,
) : ApplicationUnderTest<ConfigurableApplicationContext> {
    private lateinit var application: ConfigurableApplicationContext

    override suspend fun start(configurations: List<String>): ConfigurableApplicationContext = coroutineScope {
        val allConfigurations = (configurations + defaultConfigurations() + parameters).map { "--$it" }.toTypedArray()
        application = runner(allConfigurations)
        while (!application.isRunning || !application.isActive) {
            delay(500)
            continue
        }
        testSystem.activeSystems
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
