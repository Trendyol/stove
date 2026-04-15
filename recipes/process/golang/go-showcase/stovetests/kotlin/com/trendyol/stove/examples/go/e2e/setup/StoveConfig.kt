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
import com.trendyol.stove.process.ProcessTarget
import com.trendyol.stove.process.envMapper
import com.trendyol.stove.process.goApp
import com.trendyol.stove.system.Stove
import com.trendyol.stove.tracing.tracing
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension

private const val APP_PORT = 8090
private const val OTLP_PORT = 4317

class StoveConfig : AbstractProjectConfig() {
  override val extensions: List<Extension> = listOf(StoveKotestExtension())

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
          target = ProcessTarget.Server(port = APP_PORT, portEnvVar = "APP_PORT"),
          envProvider = envMapper {
            "database.host" to "DB_HOST"
            "database.port" to "DB_PORT"
            "database.name" to "DB_NAME"
            "database.username" to "DB_USER"
            "database.password" to "DB_PASS"
            "kafka.bootstrapServers" to "KAFKA_BROKERS"
            env("OTEL_EXPORTER_OTLP_ENDPOINT", "localhost:$OTLP_PORT")
            env("KAFKA_LIBRARY") { System.getProperty("kafka.library") ?: "sarama" }
            env("STOVE_KAFKA_BRIDGE_PORT", stoveKafkaBridgePortDefault)
          }
        )
      }.run()
  }

  override suspend fun afterProject() {
    Stove.stop()
  }
}
