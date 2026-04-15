package com.trendyol.stove.examples.go.e2e.setup

import com.trendyol.stove.dashboard.DashboardSystemOptions
import com.trendyol.stove.dashboard.dashboard
import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.http.HttpClientSystemOptions
import com.trendyol.stove.http.httpClient
import com.trendyol.stove.kafka.KafkaSystemOptions
import com.trendyol.stove.kafka.kafka
import com.trendyol.stove.kafka.stoveKafkaBridgePortDefault
import com.trendyol.stove.postgres.PostgresqlOptions
import com.trendyol.stove.postgres.postgresql
import com.trendyol.stove.system.Stove
import com.trendyol.stove.tracing.tracing
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension

private const val APP_PORT = 8090
private const val OTLP_PORT = 4317

class StoveConfig : AbstractProjectConfig() {
  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  @Suppress("LongMethod")
  override suspend fun beforeProject() {
    Stove()
      .with {
        httpClient {
          HttpClientSystemOptions(
            baseUrl = "http://localhost:$APP_PORT"
          )
        }

        dashboard {
          DashboardSystemOptions(appName = "go-showcase")
        }

        tracing {
          enableSpanReceiver(port = OTLP_PORT)
        }

        kafka {
          KafkaSystemOptions(
            configureExposedConfiguration = { cfg ->
              listOf("kafka.bootstrapServers=${cfg.bootstrapServers}")
            }
          )
        }

        postgresql {
          PostgresqlOptions(
            databaseName = "stove",
            configureExposedConfiguration = { cfg ->
              listOf(
                "database.host=${cfg.host}",
                "database.port=${cfg.port}",
                "database.name=stove",
                "database.username=${cfg.username}",
                "database.password=${cfg.password}"
              )
            }
          ).migrations {
            register<ProductMigration>()
          }
        }

        goApp(
          port = APP_PORT,
          configMapper = { configs ->
            val map =
              configs.associate { line ->
                val (key, value) = line.split("=", limit = 2)
                key to value
              }
            buildMap {
              map["database.host"]?.let { put("DB_HOST", it) }
              map["database.port"]?.let { put("DB_PORT", it) }
              map["database.name"]?.let { put("DB_NAME", it) }
              map["database.username"]?.let { put("DB_USER", it) }
              map["database.password"]?.let { put("DB_PASS", it) }
              // Pass OTLP endpoint so the Go app exports traces to Stove's receiver
              put("OTEL_EXPORTER_OTLP_ENDPOINT", "localhost:$OTLP_PORT")
              // Kafka library selection and bridge config
              put("KAFKA_LIBRARY", System.getProperty("kafka.library") ?: "sarama")
              map["kafka.bootstrapServers"]?.let { put("KAFKA_BROKERS", it) }
              put("STOVE_KAFKA_BRIDGE_PORT", stoveKafkaBridgePortDefault)
            }
          }
        )
      }.run()
  }

  override suspend fun afterProject() {
    Stove.stop()
  }
}
