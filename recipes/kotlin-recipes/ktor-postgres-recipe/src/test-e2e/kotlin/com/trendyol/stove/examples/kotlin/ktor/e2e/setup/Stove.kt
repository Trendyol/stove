package com.trendyol.stove.examples.kotlin.ktor.e2e.setup

import com.trendol.stove.testing.e2e.rdbms.postgres.*
import com.trendyol.stove.examples.kotlin.ktor.ExampleStoveKtorApp
import com.trendyol.stove.examples.kotlin.ktor.infra.boilerplate.serialization.JacksonConfiguration
import com.trendyol.stove.testing.e2e.*
import com.trendyol.stove.testing.e2e.http.*
import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.standalone.kafka.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.wiremock.*
import io.kotest.core.config.AbstractProjectConfig
import io.ktor.serialization.jackson.*
import org.koin.dsl.module

private const val DATABASE = "stove-kotlin-ktor"

class Stove : AbstractProjectConfig() {
  init {
    stoveKafkaBridgePortDefault = "50054"
  }

  override suspend fun beforeProject() {
    TestSystem()
      .with {
        httpClient {
          HttpClientSystemOptions(
            baseUrl = "http://localhost:8082",
            contentConverter = JacksonConverter(JacksonConfiguration.default)
          )
        }
        bridge()
        wiremock {
          WireMockSystemOptions(
            port = 9095
          )
        }
        kafka {
          KafkaSystemOptions(
            serde = StoveSerde.jackson.anyByteArraySerde(JacksonConfiguration.default),
            containerOptions = KafkaContainerOptions(tag = "7.8.1"),
            configureExposedConfiguration = { cfg ->
              listOf(
                "kafka.bootstrapServers=${cfg.bootstrapServers}",
                "kafka.interceptor-classes=${cfg.interceptorClass}"
              )
            }
          )
        }
        postgresql {
          PostgresqlOptions(
            databaseName = DATABASE,
            configureExposedConfiguration = { cfg ->
              listOf(
                "db.url=${toR2dbcUrl(cfg.jdbcUrl)}",
                "db.username=${cfg.username}",
                "db.password=${cfg.password}",
                "db.flyway.enabled=true",
                "db.flyway.logLevel=INFO"
              )
            }
          )
        }
        ktor(
          runner = { parameters ->
            ExampleStoveKtorApp.run(
              parameters,
              wait = false,
              module {
              }
            )
          },
          withParameters = listOf(
            "server.name=${Thread.currentThread().name}"
          )
        )
      }.run()
  }

  override suspend fun afterProject() {
    TestSystem.stop()
  }

  private fun toR2dbcUrl(url: String): String = url.replace("jdbc:", "r2dbc:")
}
