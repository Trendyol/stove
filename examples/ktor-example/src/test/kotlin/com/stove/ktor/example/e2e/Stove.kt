package com.stove.ktor.example.e2e

import com.trendol.stove.testing.e2e.rdbms.postgres.*
import com.trendyol.stove.testing.e2e.*
import com.trendyol.stove.testing.e2e.http.*
import com.trendyol.stove.testing.e2e.standalone.kafka.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.extensions.system.SystemEnvironmentProjectListener
import stove.ktor.example.app.objectMapperRef

class Stove : AbstractProjectConfig() {
  companion object {
    init {
      stoveKafkaBridgePortDefault = "50053"
    }
  }

  override fun extensions(): List<Extension> = listOf(
    SystemEnvironmentProjectListener(STOVE_KAFKA_BRIDGE_PORT, stoveKafkaBridgePortDefault)
  )

  override suspend fun beforeProject() = TestSystem().with {
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
      stoveKafkaObjectMapperRef = objectMapperRef
      KafkaSystemOptions {
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
        stove.ktor.example.run(parameters) {
          addTestSystemDependencies()
        }
      }
    )
  }.run()

  override suspend fun afterProject() {
    TestSystem.stop()
  }
}
