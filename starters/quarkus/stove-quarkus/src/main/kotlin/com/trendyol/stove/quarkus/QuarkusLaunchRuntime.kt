package com.trendyol.stove.quarkus

import com.trendyol.stove.system.Runner
import io.quarkus.runtime.Quarkus
import java.io.Closeable

internal interface QuarkusLaunchRuntime : Closeable {
  val modeName: String

  fun launch(args: Array<String>)

  fun isStarted(): Boolean

  fun stop()

  override fun close() = Unit
}

internal fun createQuarkusRuntime(runner: Runner<Unit>, bridgeEnabled: Boolean): QuarkusLaunchRuntime {
  val packagedArtifacts = findPackagedRuntimeArtifacts()
  check(!bridgeEnabled || packagedArtifacts == null) {
    "Quarkus Bridge cannot use the packaged fast-jar runtime. $QUARKUS_BRIDGE_CLASSPATH_HELP"
  }
  return packagedArtifacts?.let(::PackagedQuarkusRuntime) ?: DirectQuarkusRuntime(runner)
}

private class DirectQuarkusRuntime(private val runner: Runner<Unit>) : QuarkusLaunchRuntime {
  private val lifecycle = QuarkusRuntimeLifecycle(Quarkus::class.java.classLoader)
  override val modeName: String = "direct-main"

  override fun launch(args: Array<String>) = lifecycle.run { runner(args) }

  override fun isStarted(): Boolean = lifecycle.isStarted()

  override fun stop() = Quarkus.asyncExit()
}
