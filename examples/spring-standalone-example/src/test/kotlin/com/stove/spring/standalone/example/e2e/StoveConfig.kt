package com.stove.spring.standalone.example.e2e

import com.trendyol.stove.*
import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.http.*
import com.trendyol.stove.kafka.*
import com.trendyol.stove.postgres.*
import com.trendyol.stove.serialization.StoveSerde
import com.trendyol.stove.spring.bridge
import com.trendyol.stove.spring.springBoot
import com.trendyol.stove.system.*
import com.trendyol.stove.system.Stove
import com.trendyol.stove.wiremock.*
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import org.slf4j.*
import stove.spring.standalone.example.infrastructure.ObjectMapperConfig
import stove.spring.standalone.example.run

class StoveConfig : AbstractProjectConfig() {
  private val logger: Logger = LoggerFactory.getLogger("WireMockMonitor")

  init {
    stoveKafkaBridgePortDefault = PortFinder.findAvailablePortAsString()
  }

  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  @Suppress("LongMethod")
  override suspend fun beforeProject(): Unit =
    Stove()
      .with {
        httpClient {
          HttpClientSystemOptions(
            baseUrl = "http://localhost:8001"
          )
        }
        postgresql {
          PostgresqlOptions(
            databaseName = "stove",
            configureExposedConfiguration = { cfg ->
              listOf(
                "spring.r2dbc.url=r2dbc:postgresql://${cfg.host}:${cfg.port}/stove",
                "spring.r2dbc.username=${cfg.username}",
                "spring.r2dbc.password=${cfg.password}"
              )
            }
          ).migrations {
            register<CreateProductsTableMigration>()
          }
        }
        kafka {
          KafkaSystemOptions(
            useEmbeddedKafka = true,
            topicSuffixes = TopicSuffixes().copy(error = listOf(".error", ".DLT", "dlt")),
            serde = StoveSerde.jackson.anyByteArraySerde(ObjectMapperConfig.default),
            containerOptions = KafkaContainerOptions(tag = "7.8.1")
          ) {
            listOf(
              "kafka.bootstrapServers=${it.bootstrapServers}",
              "kafka.isSecure=false",
              "kafka.interceptorClasses=${it.interceptorClass}"
            )
          }
        }
        bridge()
        wiremock {
          WireMockSystemOptions(
            port = 9099,
            removeStubAfterRequestMatched = true,
            afterRequest = { e, _ ->
              logger.info(e.request.toString())
            }
          )
        }
        springBoot(
          runner = { parameters ->
            run(parameters)
          },
          withParameters = listOf(
            "server.port=8001",
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
