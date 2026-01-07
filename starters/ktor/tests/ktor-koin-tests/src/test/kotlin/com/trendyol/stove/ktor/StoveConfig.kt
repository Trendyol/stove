package com.trendyol.stove.ktor

import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension

class KoinStove : AbstractProjectConfig() {
  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  override suspend fun beforeProject(): Unit =
    Stove()
      .with {
        bridge() // Auto-detects Koin
        ktor(
          runner = { params ->
            KoinTestApp.run(params)
          }
        )
      }.run()

  override suspend fun afterProject(): Unit = Stove.stop()
}

class KoinBridgeSystemTests : BridgeSystemTests(KoinStove())
