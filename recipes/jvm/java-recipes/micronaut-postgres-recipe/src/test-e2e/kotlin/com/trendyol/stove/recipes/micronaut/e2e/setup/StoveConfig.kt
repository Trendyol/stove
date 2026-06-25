package com.trendyol.stove.recipes.micronaut.e2e.setup

import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.http.HttpClientSystemOptions
import com.trendyol.stove.http.httpClient
import com.trendyol.stove.micronaut.bridge
import com.trendyol.stove.micronaut.micronaut
import com.trendyol.stove.postgres.PostgresqlOptions
import com.trendyol.stove.postgres.postgresql
import com.trendyol.stove.recipes.micronaut.Application
import com.trendyol.stove.system.Stove
import com.trendyol.stove.tracing.tracing
import com.trendyol.stove.wiremock.WireMockSystemOptions
import com.trendyol.stove.wiremock.wiremock
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension

/**
 * Kotest project configuration that wires the Stove TestSystem for the Micronaut recipe.
 *
 * Demonstrates HTTP, PostgreSQL (r2dbc), WireMock, tracing and the Micronaut bridge (`using<T>`).
 */
class StoveConfig : AbstractProjectConfig() {
  private val appPort = 8092

  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  override suspend fun beforeProject() {
    Stove()
      .with {
        httpClient {
          HttpClientSystemOptions(
            baseUrl = "http://localhost:$appPort"
          )
        }
        postgresql {
          PostgresqlOptions(
            databaseName = "stove",
            configureExposedConfiguration = { cfg ->
              listOf(
                "r2dbc.datasources.default.url=r2dbc:postgresql://${cfg.host}:${cfg.port}/stove",
                "r2dbc.datasources.default.username=${cfg.username}",
                "r2dbc.datasources.default.password=${cfg.password}"
              )
            }
          ).migrations {
            register<CreateProductsTableMigration>()
          }
        }
        bridge()
        tracing {
          enableSpanReceiver()
        }
        wiremock {
          WireMockSystemOptions(
            port = 0,
            removeStubAfterRequestMatched = true,
            configureExposedConfiguration = { cfg ->
              listOf("micronaut.http.services.lookup-api.url=${cfg.baseUrl}")
            }
          )
        }
        micronaut(
          runner = { parameters ->
            Application.run(parameters)
          },
          withParameters = listOf(
            "micronaut.server.port=$appPort",
            "logging.level.root=info"
          )
        )
      }.run()
  }

  override suspend fun afterProject(): Unit = Stove.stop()
}
