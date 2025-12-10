package com.trendyol.stove.recipes.quarkus.e2e.setup

import com.trendyol.stove.recipes.quarkus.QuarkusMainApp
import com.trendyol.stove.testing.e2e.http.*
import com.trendyol.stove.testing.e2e.system.*
import io.kotest.core.config.AbstractProjectConfig

/**
 * Kotest project configuration that sets up Stove TestSystem for Quarkus e2e tests.
 */
class Stove : AbstractProjectConfig() {
  override suspend fun beforeProject() {
    TestSystem()
      .with {
        httpClient {
          HttpClientSystemOptions(
            baseUrl = "http://localhost:8040"
          )
        }
        bridge()
        quarkus(
          runner = { params ->
            QuarkusMainApp.main(params)
          },
          withParameters = listOf(
            "quarkus.http.port=8040"
          )
        )
      }.run()
  }

  override suspend fun afterProject() {
    TestSystem.stop()
  }
}
