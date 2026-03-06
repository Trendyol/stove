package com.trendyol.stove.recipes.quarkus.e2e.setup

import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.http.*
import com.trendyol.stove.recipes.quarkus.QuarkusMainApp
import com.trendyol.stove.system.*
import com.trendyol.stove.tracing.tracing
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension

/**
 * Kotest project configuration that sets up Stove TestSystem for Quarkus e2e tests.
 */
class StoveConfig : AbstractProjectConfig() {
  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  override suspend fun beforeProject() {
    Stove()
      .with {
        tracing {
          enableSpanReceiver()
        }
        httpClient {
          HttpClientSystemOptions(
            baseUrl = "http://localhost:8040"
          )
        }
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
