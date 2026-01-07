package com.stove.ktor.example.e2e

import com.trendyol.stove.*
import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.http.*
import com.trendyol.stove.kafka.*
import com.trendyol.stove.ktor.bridge
import com.trendyol.stove.ktor.ktor
import com.trendyol.stove.postgres.*
import com.trendyol.stove.serialization.StoveSerde
import com.trendyol.stove.system.*
import com.trendyol.stove.system.Stove
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import stove.ktor.example.app.objectMapperRef
import stove.ktor.example.run

class StoveConfig : AbstractProjectConfig() {
  companion object {
    init {
      stoveKafkaBridgePortDefault = PortFinder.findAvailablePortAsString()
      System.setProperty(STOVE_KAFKA_BRIDGE_PORT, stoveKafkaBridgePortDefault)
    }
  }

  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  override suspend fun beforeProject() = Stove()
    .with {
      httpClient {
        HttpClientSystemOptions(
          baseUrl = "http://localhost:8080"
        )
      }
      bridge()
      postgresql {
        PostgresqlOptions(configureExposedConfiguration = { cfg ->
          listOf(
            "database.jdbcUrl=${cfg.jdbcUrl}",
            "database.host=${cfg.host}",
            "database.port=${cfg.port}",
            "database.username=${cfg.username}",
            "database.password=${cfg.password}"
          )
        })
      }
      kafka {
        KafkaSystemOptions(
          serde = StoveSerde.jackson.anyByteArraySerde(objectMapperRef),
          containerOptions = KafkaContainerOptions(tag = "7.8.1")
        ) {
          listOf(
            "kafka.bootstrapServers=${it.bootstrapServers}",
            "kafka.interceptorClasses=${it.interceptorClass}"
          )
        }
      }
      ktor(
        withParameters = listOf(
          "port=8080"
        ),
        runner = { parameters ->
          run(parameters) {
            addTestSystemDependencies()
          }
        }
      )
    }.run()

  override suspend fun afterProject() {
    Stove.stop()
  }
}
