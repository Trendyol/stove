package com.trendyol.stove.recipes.quarkus.e2e.setup

import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.http.*
import com.trendyol.stove.quarkus.quarkus
import com.trendyol.stove.recipes.quarkus.QuarkusMainApp
import com.trendyol.stove.system.*
import com.trendyol.stove.tracing.tracing
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension

/**
 * Kotest project configuration that sets up Stove TestSystem for Quarkus e2e tests.
 */
class StoveConfig : AbstractProjectConfig() {
  private val appPort = PortFinder.findAvailablePort()

  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  override suspend fun beforeProject() {
    Stove()
      .with {
        tracing {
          enableSpanReceiver()
        }
        httpClient {
          HttpClientSystemOptions(
            baseUrl = "http://localhost:$appPort"
          )
        }
        quarkus(
          runner = { params ->
            QuarkusMainApp.main(params)
          },
          withParameters = listOf(
            "quarkus.http.port=$appPort"
          )
        )
      }.run()
  }

  override suspend fun afterProject() {
    Stove.stop()
  }
}
