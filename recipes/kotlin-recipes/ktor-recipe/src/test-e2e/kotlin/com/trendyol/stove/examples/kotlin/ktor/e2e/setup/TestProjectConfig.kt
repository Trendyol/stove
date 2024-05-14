package com.trendyol.stove.examples.kotlin.ktor.e2e.setup

import com.trendyol.stove.examples.kotlin.ktor.ExampleStoveKtorApp
import com.trendyol.stove.examples.kotlin.ktor.infra.boilerplate.serialization.JacksonConfiguration
import com.trendyol.stove.examples.kotlin.ktor.infra.components.product.persistency.MongoProductRepository.Companion.PRODUCT_COLLECTION
import com.trendyol.stove.testing.e2e.*
import com.trendyol.stove.testing.e2e.http.*
import com.trendyol.stove.testing.e2e.mongodb.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.wiremock.*
import io.kotest.core.config.AbstractProjectConfig
import org.koin.dsl.module

private val database = "stove-kotlin-ktor"

class TestProjectConfig : AbstractProjectConfig() {
  override suspend fun beforeProject() {
    TestSystem("http://localhost:8081").with {
      httpClient {
        HttpClientSystemOptions(
          objectMapper = JacksonConfiguration.default
        )
      }
      bridge()
      wiremock {
        WireMockSystemOptions(
          port = 9090
        )
      }
      mongodb {
        MongodbSystemOptions(
          databaseOptions = DatabaseOptions(DatabaseOptions.DefaultDatabase(database, collection = PRODUCT_COLLECTION)),
          container = MongoContainerOptions(),
          objectMapper = JacksonConfiguration.default,
          configureExposedConfiguration = { cfg ->
            listOf(
              "mongo.database=$database",
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
    TestSystem.stop()
  }
}
