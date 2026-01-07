package com.trendyol.stove.wiremock

import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.system.stove
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension

class StoveConfig : AbstractProjectConfig() {
  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  override suspend fun beforeProject(): Unit =
    Stove()
      .with {
        wiremock {
          WireMockSystemOptions(
            port = 9098,
            removeStubAfterRequestMatched = true
          )
        }
        applicationUnderTest(
          object : ApplicationUnderTest<Unit> {
            override suspend fun start(configurations: List<String>) = Unit

            override suspend fun stop() = Unit
          }
        )
      }.run()

  override suspend fun afterProject(): Unit = Stove.stop()
}
