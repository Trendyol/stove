package com.stove.micronaut.example.e2e

import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.http.*
import com.trendyol.stove.micronaut.*
import com.trendyol.stove.postgres.*
import com.trendyol.stove.system.PortFinder
import com.trendyol.stove.system.Stove
import com.trendyol.stove.tracing.tracing
import com.trendyol.stove.wiremock.*
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import org.slf4j.*
import stove.micronaut.example.run as runMicronautApp

class StoveConfig : AbstractProjectConfig() {
  private val appPort = PortFinder.findAvailablePort()

  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  private val logger: Logger = LoggerFactory.getLogger("WireMockMonitor")

  @Suppress("LongMethod")
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
            afterRequest = { e, _ ->
              logger.info(e.request.toString())
            },
            configureExposedConfiguration = { cfg ->
              listOf("micronaut.http.services.lookup-api.url=${cfg.baseUrl}")
            }
          )
        }
        micronaut(
          runner = { parameters ->
            runMicronautApp(parameters) {
            }
          },
          withParameters = listOf(
            "micronaut.server.port=$appPort",
            "logging.level.root=info",
            "logging.level.org.micronaut.web=info"
          )
        )
      }.run()
  }

  override suspend fun afterProject(): Unit = Stove.stop()
}
