package com.trendyol.stove.testing.e2e.ktor

import com.trendyol.stove.testing.e2e.bridge
import com.trendyol.stove.testing.e2e.ktor
import com.trendyol.stove.testing.e2e.reporting.StoveKotestExtension
import com.trendyol.stove.testing.e2e.system.TestSystem
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension

class KoinStove : AbstractProjectConfig() {
  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  override suspend fun beforeProject(): Unit =
    TestSystem()
      .with {
        bridge() // Auto-detects Koin
        ktor(
          runner = { params ->
            KoinTestApp.run(params)
          }
        )
      }.run()

  override suspend fun afterProject(): Unit = TestSystem.stop()
}

class KoinBridgeSystemTests : BridgeSystemTests(KoinStove())
