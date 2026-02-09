package com.stove.spring.example.e2e

import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.http.*
import com.trendyol.stove.kafka.*
import com.trendyol.stove.postgres.*
import com.trendyol.stove.spring.*
import com.trendyol.stove.system.PortFinder
import com.trendyol.stove.system.Stove
import com.trendyol.stove.tracing.tracing
import com.trendyol.stove.wiremock.*
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import org.slf4j.*
import stove.spring.example.run

class StoveConfig : AbstractProjectConfig() {
  private val appPort = PortFinder.findAvailablePort()

  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  private val logger: Logger = LoggerFactory.getLogger("WireMockMonitor")

  @Suppress("LongMethod")
  override suspend fun beforeProject(): Unit =
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
        postgresql {
          PostgresqlOptions(
            databaseName = "stove",
            configureExposedConfiguration = { cfg ->
              listOf(
                "spring.datasource.url=${cfg.jdbcUrl}",
                "spring.datasource.username=${cfg.username}",
                "spring.datasource.password=${cfg.password}"
              )
            }
          ).migrations {
            register<CreateProductsTableMigration>()
          }
        }
        kafka {
          KafkaSystemOptions(
            containerOptions = KafkaContainerOptions(tag = "8.0.3"),
            configureExposedConfiguration = {
              listOf(
                "kafka.bootstrapServers=${it.bootstrapServers}",
                "kafka.isSecure=false"
              )
            }
          )
        }
        bridge()
        wiremock {
          WireMockSystemOptions(
            port = 0,
            removeStubAfterRequestMatched = true,
            afterRequest = { e, _ ->
              logger.info(e.request.toString())
            },
            configureExposedConfiguration = { cfg ->
              listOf("http-clients.supplier-http.url=${cfg.baseUrl}")
            }
          )
        }

        springBoot(
          runner = { parameters ->
            run(parameters) {
              this.addTestSystemDependencies()
            }
          },
          withParameters = listOf(
            "server.port=$appPort",
            "logging.level.root=info",
            "logging.level.org.springframework.web=info",
            "spring.profiles.active=default",
            "kafka.heartbeatInSeconds=2",
            "kafka.autoCreateTopics=true",
            "kafka.offset=earliest",
            "kafka.secureKafka=false"
          )
        )
      }.run()

  override suspend fun afterProject(): Unit = Stove.stop()
}
