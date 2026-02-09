package com.stove.ktor.example.e2e

import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.http.*
import com.trendyol.stove.kafka.*
import com.trendyol.stove.ktor.*
import com.trendyol.stove.postgres.*
import com.trendyol.stove.serialization.StoveSerde
import com.trendyol.stove.system.*
import com.trendyol.stove.testing.grpcmock.*
import com.trendyol.stove.tracing.tracing
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import stove.ktor.example.app.objectMapperRef
import stove.ktor.example.run

class StoveConfig : AbstractProjectConfig() {
  companion object {
    private val appPort = PortFinder.findAvailablePort()

    init {
      stoveKafkaBridgePortDefault = PortFinder.findAvailablePortAsString()
      System.setProperty(STOVE_KAFKA_BRIDGE_PORT, stoveKafkaBridgePortDefault)
    }
  }

  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  @Suppress("LongMethod")
  override suspend fun beforeProject() = Stove()
    .with {
      httpClient {
        HttpClientSystemOptions(
          baseUrl = "http://localhost:$appPort"
        )
      }
      bridge()
      tracing {
        enableSpanReceiver()
      }
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
          containerOptions = KafkaContainerOptions(tag = "8.0.3")
        ) {
          listOf(
            "kafka.bootstrapServers=${it.bootstrapServers}",
            "kafka.interceptorClasses=${it.interceptorClass}"
          )
        }
      }

      // =====================================================
      // Single gRPC mock server for ALL external gRPC services
      // Uses dynamic port (0) to avoid CI conflicts
      // =====================================================
      grpcMock {
        GrpcMockSystemOptions(
          // port = 0 by default (dynamic port)
          removeStubAfterRequestMatched = true,
          configureExposedConfiguration = { cfg ->
            // Both gRPC clients in the app point to the SAME mock server
            listOf(
              "featureToggle.host=${cfg.host}",
              "featureToggle.port=${cfg.port}",
              "pricing.host=${cfg.host}",
              "pricing.port=${cfg.port}"
            )
          }
        )
      }

      ktor(
        withParameters = listOf(
          "port=$appPort"
          // gRPC settings are now auto-injected via grpcMock's configureExposedConfiguration
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
