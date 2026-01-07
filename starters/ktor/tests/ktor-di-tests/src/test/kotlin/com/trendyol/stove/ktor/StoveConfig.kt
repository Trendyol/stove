package com.trendyol.stove.ktor

import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension

class KtorDiStove : AbstractProjectConfig() {
  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  override suspend fun beforeProject(): Unit =
    Stove()
      .with {
        bridge() // Auto-detects Ktor-DI
        ktor(
          runner = { params ->
            KtorDiTestApp.run(params)
          }
        )
      }.run()

  override suspend fun afterProject(): Unit = Stove.stop()
}

class KtorDiBridgeSystemTests : BridgeSystemTests(KtorDiStove())
