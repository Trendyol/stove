package com.trendyol.stove.recipes.quarkus.e2e.setup

import com.trendyol.stove.http.*
import com.trendyol.stove.recipes.quarkus.QuarkusMainApp
import com.trendyol.stove.system.*
import io.kotest.core.config.AbstractProjectConfig

/**
 * Kotest project configuration that sets up Stove TestSystem for Quarkus e2e tests.
 */
class StoveConfig : AbstractProjectConfig() {
  override suspend fun beforeProject() {
    Stove()
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
    Stove.stop()
  }
}
