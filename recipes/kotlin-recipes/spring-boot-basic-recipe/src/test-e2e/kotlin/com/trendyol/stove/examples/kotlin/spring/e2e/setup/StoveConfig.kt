package com.trendyol.stove.examples.kotlin.spring.e2e.setup

import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.grpc.*
import com.trendyol.stove.http.*
import com.trendyol.stove.kafka.*
import com.trendyol.stove.postgres.*
import com.trendyol.stove.serialization.StoveSerde
import com.trendyol.stove.spring.*
import com.trendyol.stove.system.Stove
import com.trendyol.stove.testing.grpcmock.*
import com.trendyol.stove.tracing.tracing
import com.trendyol.stove.wiremock.*
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import org.springframework.kafka.support.serializer.JsonSerializer

const val GRPC_MOCK_PORT = 9092
const val GRPC_SERVER_PORT = 50051

class StoveConfig : AbstractProjectConfig() {
  init {
    stoveKafkaBridgePortDefault = "50053"
    System.setProperty(STOVE_KAFKA_BRIDGE_PORT, stoveKafkaBridgePortDefault)
  }

  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  override suspend fun beforeProject() {
    Stove()
      .with {
        httpClient {
          HttpClientSystemOptions(
            baseUrl = "http://localhost:8024"
          )
        }

        bridge()

        // Enable tracing - starts OTLP gRPC receiver
        // Service name is automatically extracted from incoming spans (set by OTel agent)
        tracing {
          enableSpanReceiver()
        }

        // gRPC Mock for external gRPC services (Fraud Detection)
        grpcMock {
          GrpcMockSystemOptions(port = GRPC_MOCK_PORT)
        }

        // gRPC Client for testing OUR gRPC server (OrderQueryService)
        grpc {
          GrpcSystemOptions(
            host = "localhost",
            port = GRPC_SERVER_PORT
          )
        }

        wiremock {
          WireMockSystemOptions(
            port = 9091,
            serde = StoveSerde.jackson.anyByteArraySerde()
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
            register<CreateOrdersTableMigration>()
          }
        }

        kafka {
          KafkaSystemOptions(
            serde = StoveSerde.jackson.anyByteArraySerde(),
            valueSerializer = JsonSerializer(),
            containerOptions = KafkaContainerOptions(tag = "7.8.1") {
              withStartupAttempts(3)
            },
            configureExposedConfiguration = { cfg ->
              listOf(
                "spring.kafka.bootstrap-servers=${cfg.bootstrapServers}",
                "spring.kafka.producer.properties.interceptor.classes=${cfg.interceptorClass}"
              )
            }
          )
        }

        springBoot(
          runner = { params ->
            com.trendyol.stove.examples.kotlin.spring.run(params)
          },
          withParameters = listOf(
            "server.port=8024",
            "grpc.server.port=$GRPC_SERVER_PORT",
            "external-apis.inventory.url=http://localhost:9091",
            "external-apis.payment.url=http://localhost:9091",
            "external-apis.fraud-detection.host=localhost",
            "external-apis.fraud-detection.port=$GRPC_MOCK_PORT"
          )
        )
      }.run()
  }

  override suspend fun afterProject() {
    Stove.stop()
  }
}
