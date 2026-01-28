package com.trendyol.stove.example.java.spring.e2e.setup

import com.trendyol.stove.examples.java.spring.ExampleSpringBootApp
import com.trendyol.stove.examples.java.spring.infra.boilerplate.serialization.JacksonConfiguration
import com.trendyol.stove.http.*
import com.trendyol.stove.kafka.*
import com.trendyol.stove.postgres.*
import com.trendyol.stove.serialization.StoveSerde
import com.trendyol.stove.spring.*
import com.trendyol.stove.system.Stove
import com.trendyol.stove.wiremock.*
import io.kotest.core.config.AbstractProjectConfig
import io.ktor.serialization.jackson.*
import org.springframework.kafka.support.serializer.JsonSerializer

class Stove : AbstractProjectConfig() {
  init {
    stoveKafkaBridgePortDefault = "50052"
    System.setProperty(STOVE_KAFKA_BRIDGE_PORT, stoveKafkaBridgePortDefault)
  }

  override suspend fun beforeProject() {
    Stove()
      .with {
        httpClient {
          HttpClientSystemOptions(
            baseUrl = "http://localhost:8080",
            contentConverter = JacksonConverter(JacksonConfiguration.defaultObjectMapper())
          )
        }

        bridge()
        wiremock {
          WireMockSystemOptions(
            port = 9091,
            serde = StoveSerde.jackson.anyByteArraySerde(JacksonConfiguration.defaultObjectMapper())
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
            serde = StoveSerde.jackson.anyByteArraySerde(JacksonConfiguration.defaultObjectMapper()),
            valueSerializer = JsonSerializer(JacksonConfiguration.defaultObjectMapper()),
            containerOptions = KafkaContainerOptions(tag = "7.8.1") {
              withStartupAttempts(3)
            },
            configureExposedConfiguration = {
              listOf(
                "kafka.bootstrap-servers=${it.bootstrapServers}",
                "kafka.interceptor-classes=${it.interceptorClass}"
              )
            }
          )
        }
        springBoot(
          runner = { parameters ->
            ExampleSpringBootApp.run(parameters) {
            }
          },
          withParameters = listOf()
        )
      }.run()
  }

  override suspend fun afterProject() {
    Stove.stop()
  }
}
