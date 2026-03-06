package com.stove.quarkus.example.e2e

import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.http.HttpClientSystemOptions
import com.trendyol.stove.http.httpClient
import com.trendyol.stove.kafka.KafkaContainerOptions
import com.trendyol.stove.kafka.KafkaMigration
import com.trendyol.stove.kafka.KafkaMigrationContext
import com.trendyol.stove.kafka.KafkaSystemOptions
import com.trendyol.stove.kafka.kafka
import com.trendyol.stove.postgres.PostgresqlOptions
import com.trendyol.stove.postgres.postgresql
import com.trendyol.stove.quarkus.quarkus
import com.trendyol.stove.serialization.StoveSerde
import com.trendyol.stove.system.PortFinder
import com.trendyol.stove.system.Stove
import com.trendyol.stove.tracing.tracing
import com.trendyol.stove.wiremock.WireMockSystemOptions
import com.trendyol.stove.wiremock.wiremock
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import org.apache.kafka.clients.admin.NewTopic
import stove.quarkus.example.QuarkusMainApp

class StoveConfig : AbstractProjectConfig() {
  companion object {
    private val appPort = PortFinder.findAvailablePort()
  }

  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  @Suppress("LongMethod")
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
        postgresql {
          PostgresqlOptions(
            databaseName = "stove",
            configureExposedConfiguration = { cfg ->
              listOf(
                "quarkus.datasource.jdbc.url=${cfg.jdbcUrl}",
                "quarkus.datasource.username=${cfg.username}",
                "quarkus.datasource.password=${cfg.password}"
              )
            }
          )
        }
        kafka {
          KafkaSystemOptions(
            serde = StoveSerde.jackson.anyByteArraySerde(),
            containerOptions = KafkaContainerOptions(tag = "8.0.3")
          ) {
            listOf(
              "kafka.bootstrap.servers=${it.bootstrapServers}",
              "app.kafka.bridge-interceptor-class=${it.interceptorClass}"
            )
          }.migrations {
            register<CreateQuarkusExampleTopicsMigration>()
          }
        }
        wiremock {
          WireMockSystemOptions(
            port = 0,
            removeStubAfterRequestMatched = true,
            configureExposedConfiguration = { cfg ->
              listOf("clients.supplier.url=${cfg.baseUrl}")
            }
          )
        }
        quarkus(
          runner = { params ->
            QuarkusMainApp.main(params)
          },
          withParameters = listOf("quarkus.http.port=$appPort")
        )
      }.run()
  }

  override suspend fun afterProject() {
    Stove.stop()
  }
}

class CreateQuarkusExampleTopicsMigration : KafkaMigration {
  override val order: Int = 1

  override suspend fun execute(connection: KafkaMigrationContext) {
    connection.admin
      .createTopics(
        listOf(
          NewTopic("trendyol.stove.service.product.create.0", 1, 1),
          NewTopic("trendyol.stove.service.product.created.0", 1, 1)
        )
      ).all()
      .get()
  }
}
