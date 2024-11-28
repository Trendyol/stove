package com.trendyol.stove.testing.e2e.wiremock

import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import io.kotest.core.config.AbstractProjectConfig

class Stove : AbstractProjectConfig() {
  override suspend fun beforeProject(): Unit =
    TestSystem()
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

  override suspend fun afterProject(): Unit = TestSystem.stop()
}
