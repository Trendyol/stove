package com.trendyol.stove.testing.e2e.ktor

import com.trendyol.stove.testing.e2e.bridge
import com.trendyol.stove.testing.e2e.ktor
import com.trendyol.stove.testing.e2e.system.TestSystem
import io.kotest.core.config.AbstractProjectConfig

class KtorDiStove : AbstractProjectConfig() {
  override suspend fun beforeProject(): Unit =
    TestSystem()
      .with {
        bridge() // Auto-detects Ktor-DI
        ktor(
          runner = { params ->
            KtorDiTestApp.run(params)
          }
        )
      }.run()

  override suspend fun afterProject(): Unit = TestSystem.stop()
}

class KtorDiBridgeSystemTests : BridgeSystemTests(KtorDiStove())
