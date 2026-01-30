package com.stove.ktor.example.e2e

import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.grpc.*
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
    /**
     * Single gRPC mock server port that handles MULTIPLE services:
     * - FeatureToggleService (featuretoggle.FeatureToggleService)
     * - PricingService (pricing.PricingService)
     *
     * The mock server uses a dynamic handler registry that routes
     * requests to the correct stub based on the service/method name.
     */
    const val GRPC_MOCK_PORT = 9097

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
          baseUrl = "http://localhost:8080"
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
          containerOptions = KafkaContainerOptions(tag = "7.8.1")
        ) {
          listOf(
            "kafka.bootstrapServers=${it.bootstrapServers}",
            "kafka.interceptorClasses=${it.interceptorClass}"
          )
        }
      }

      // =====================================================
      // Single gRPC mock server for ALL external gRPC services
      // =====================================================
      grpcMock {
        GrpcMockSystemOptions(
          port = GRPC_MOCK_PORT,
          removeStubAfterRequestMatched = true
        )
      }

      // gRPC client for calling mocked services (optional, for direct testing)
      grpc {
        GrpcSystemOptions(
          host = "localhost",
          port = GRPC_MOCK_PORT
        )
      }

      ktor(
        withParameters = listOf(
          "port=8080",
          // Both gRPC clients point to the SAME mock server
          "featureToggle.host=localhost",
          "featureToggle.port=$GRPC_MOCK_PORT",
          "pricing.host=localhost",
          "pricing.port=$GRPC_MOCK_PORT"
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
