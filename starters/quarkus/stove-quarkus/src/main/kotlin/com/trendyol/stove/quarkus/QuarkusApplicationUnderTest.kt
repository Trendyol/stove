package com.trendyol.stove.quarkus

import com.trendyol.stove.system.Runner
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.WithDsl
import com.trendyol.stove.system.abstractions.AfterRunAwareWithContext
import com.trendyol.stove.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.system.abstractions.ReadyStove
import com.trendyol.stove.system.annotations.StoveDsl
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal fun Stove.systemUnderTest(
  runner: Runner<Unit>,
  withParameters: List<String> = listOf()
): ReadyStove {
  this.applicationUnderTest(QuarkusApplicationUnderTest(this, runner, withParameters))
  return this
}

fun WithDsl.quarkus(
  runner: Runner<Unit>,
  withParameters: List<String> = listOf()
): ReadyStove = this.stove.systemUnderTest(runner, withParameters)

@StoveDsl
class QuarkusApplicationUnderTest(
  private val stove: Stove,
  private val runner: Runner<Unit>,
  private val parameters: List<String>
) : ApplicationUnderTest<Unit> {
  private var launcher: QuarkusLauncher? = null

  @Suppress("TooGenericExceptionCaught")
  override suspend fun start(configurations: List<String>) {
    val quarkusLauncher = createLauncher(configurations)
    launcher = quarkusLauncher

    try {
      quarkusLauncher.start()
      notifySystemsAfterRun()
    } catch (error: Throwable) {
      stopAfterStartupFailure(quarkusLauncher, error)
      launcher = null
      throw error
    }
  }

  override suspend fun stop() {
    launcher?.stop()
    launcher = null
  }

  private suspend fun notifySystemsAfterRun() {
    coroutineScope {
      stove.systemsOf<AfterRunAwareWithContext<Unit>>()
        .map { async { it.afterRun(Unit) } }
        .awaitAll()
    }
  }

  private fun createLauncher(configurations: List<String>) = QuarkusLauncher(
    runtime = createQuarkusRuntime(runner, stove.systemsOf<QuarkusBridgeSystem>().isNotEmpty()),
    configurations = configurations + parameters
  )

  @Suppress("TooGenericExceptionCaught")
  private fun stopAfterStartupFailure(quarkusLauncher: QuarkusLauncher, error: Throwable) {
    try {
      quarkusLauncher.stop()
    } catch (stopError: Throwable) {
      error.addSuppressed(stopError)
    }
  }
}
