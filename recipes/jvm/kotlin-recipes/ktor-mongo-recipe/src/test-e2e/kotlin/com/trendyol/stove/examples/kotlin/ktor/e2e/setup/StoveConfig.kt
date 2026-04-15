package com.trendyol.stove.examples.kotlin.ktor.e2e.setup

import com.trendyol.stove.examples.kotlin.ktor.ExampleStoveKtorApp
import com.trendyol.stove.examples.kotlin.ktor.infra.boilerplate.serialization.JacksonConfiguration
import com.trendyol.stove.examples.kotlin.ktor.infra.components.product.persistency.MongoProductRepository.Companion.PRODUCT_COLLECTION
import com.trendyol.stove.http.*
import com.trendyol.stove.kafka.*
import com.trendyol.stove.ktor.*
import com.trendyol.stove.mongodb.*
import com.trendyol.stove.serialization.StoveSerde
import com.trendyol.stove.system.Stove
import com.trendyol.stove.wiremock.*
import io.kotest.core.config.AbstractProjectConfig
import io.ktor.serialization.jackson.*
import org.koin.dsl.module

private const val DATABASE = "stove-kotlin-ktor"

class StoveConfig : AbstractProjectConfig() {
  override suspend fun beforeProject() {
    Stove()
      .with {
        httpClient {
          HttpClientSystemOptions(
            baseUrl = "http://localhost:8081",
            contentConverter = JacksonConverter(JacksonConfiguration.default)
          )
        }
        bridge()
        wiremock {
          WireMockSystemOptions(
            port = 9090
          )
        }
        kafka {
          KafkaSystemOptions(
            serde = StoveSerde.jackson.anyByteArraySerde(JacksonConfiguration.default),
            containerOptions = KafkaContainerOptions(tag = "8.0.3"),
            configureExposedConfiguration = { cfg ->
              listOf(
                "kafka.bootstrapServers=${cfg.bootstrapServers}",
                "kafka.interceptor-classes=${cfg.interceptorClass}"
              )
            }
          )
        }
        mongodb {
          MongodbSystemOptions(
            databaseOptions = DatabaseOptions(DatabaseOptions.DefaultDatabase(DATABASE, collection = PRODUCT_COLLECTION)),
            container = MongoContainerOptions(),
            configureExposedConfiguration = { cfg ->
              listOf(
                "mongo.database=$DATABASE",
                "mongo.uri=${cfg.connectionString}/?retryWrites=true&w=majority"
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
    Stove.stop()
  }
}
