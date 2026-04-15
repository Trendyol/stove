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
            port = 0, // Dynamic port allocation for CI compatibility
            serde = StoveSerde.jackson.anyByteArraySerde(),
            configureExposedConfiguration = { cfg ->
              listOf(
                "external-apis.inventory.url=${cfg.baseUrl}",
                "external-apis.payment.url=${cfg.baseUrl}"
              )
            }
          )
        }

        postgresql {
          PostgresqlOptions(
            databaseName = "stove",
            configureExposedConfiguration = { cfg ->
              listOf(
                // R2DBC configuration for reactive database access
                "spring.r2dbc.url=r2dbc:postgresql://${cfg.host}:${cfg.port}/stove",
                "spring.r2dbc.username=${cfg.username}",
                "spring.r2dbc.password=${cfg.password}",
                // JDBC configuration for db-scheduler
                "spring.datasource.url=jdbc:postgresql://${cfg.host}:${cfg.port}/stove",
                "spring.datasource.username=${cfg.username}",
                "spring.datasource.password=${cfg.password}"
              )
            }
          ).migrations {
            register<OrderExampleInitialMigration>()
          }
        }

        kafka {
          KafkaSystemOptions(
            serde = StoveSerde.jackson.anyByteArraySerde(),
            valueSerializer = JsonSerializer(),
            containerOptions = KafkaContainerOptions(tag = "8.0.3") {
              withStartupAttempts(3)
            },
            configureExposedConfiguration = { cfg ->
              listOf(
                "spring.kafka.bootstrap-servers=${cfg.bootstrapServers}",
                "spring.kafka.producer.properties.interceptor.classes=${cfg.interceptorClass}",
                "spring.kafka.consumer.properties.interceptor.classes=${cfg.interceptorClass}"
              )
            }
          )
        }

        // db-scheduler system for testing scheduled tasks
        dbScheduler()

        springBoot(
          runner = { params ->
            com.trendyol.stove.examples.kotlin.spring
              .run(params) {
                // Register test-specific beans for db-scheduler
                addTestDependencies {
                  bean<StoveDbSchedulerListener>(isPrimary = true)
                }
              }
          },
          withParameters = listOf(
            "server.port=8024",
            "grpc.server.port=$GRPC_SERVER_PORT",
            "external-apis.fraud-detection.host=localhost",
            "external-apis.fraud-detection.port=$GRPC_MOCK_PORT"
            // WireMock URLs are set via configureExposedConfiguration for dynamic port support
          )
        )
      }.run()
  }

  override suspend fun afterProject() {
    Stove.stop()
  }
}
