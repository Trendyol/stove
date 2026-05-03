package com.trendyol.stove.examples.go.e2e.setup

import com.trendyol.stove.container.ContainerApplicationOptions
import com.trendyol.stove.container.ContainerTarget
import com.trendyol.stove.container.containerApp
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
import com.trendyol.stove.process.envMapper as processEnvMapper
import com.trendyol.stove.process.goApp
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.application.envMapper as containerEnvMapper
import com.trendyol.stove.tracing.tracing
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import java.io.File

private const val APP_PORT = 8090
private const val OTLP_PORT = 4317
private const val COVERAGE_DIR_IN_CONTAINER = "/tmp/go-coverage"

private enum class GoAutMode {
  Process,
  Container
}

class StoveConfig : AbstractProjectConfig() {
  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  override suspend fun beforeProject() {
    val autMode = resolveAutMode()
    val appImage = System.getProperty("go.app.container.image").orEmpty()
    val kafkaLibrary = System.getProperty("kafka.library") ?: "sarama"
    val hostCoverageDir = System.getProperty("go.cover.dir").orEmpty()
    val coverageDirInContainer = if (hostCoverageDir.isBlank()) "" else COVERAGE_DIR_IN_CONTAINER

    Stove()
      .with {
        httpClient {
          HttpClientSystemOptions(baseUrl = "http://localhost:$APP_PORT")
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

        when (autMode) {
          GoAutMode.Process -> goApp(
            target = ProcessTarget.Server(port = APP_PORT, portEnvVar = "APP_PORT"),
            envProvider = processEnvMapper {
              "database.host" to "DB_HOST"
              "database.port" to "DB_PORT"
              "database.name" to "DB_NAME"
              "database.username" to "DB_USER"
              "database.password" to "DB_PASS"
              "kafka.bootstrapServers" to "KAFKA_BROKERS"
              env("OTEL_EXPORTER_OTLP_ENDPOINT", "localhost:$OTLP_PORT")
              env("KAFKA_LIBRARY", kafkaLibrary)
              env("STOVE_KAFKA_BRIDGE_PORT", stoveKafkaBridgePortDefault)
              env("GOCOVERDIR") {
                hostCoverageDir.takeIf { it.isNotBlank() }?.also { File(it).mkdirs() } ?: ""
              }
            }
          )

          GoAutMode.Container -> {
            require(appImage.isNotBlank()) { "go.app.container.image system property not set" }
            containerApp {
              ContainerApplicationOptions(
                image = appImage,
                target = ContainerTarget.Server(
                  hostPort = APP_PORT,
                  internalPort = APP_PORT,
                  portEnvVar = "APP_PORT",
                  bindHostPort = false
                ),
                envProvider = containerEnvMapper {
                  "database.host" to "DB_HOST"
                  "database.port" to "DB_PORT"
                  "database.name" to "DB_NAME"
                  "database.username" to "DB_USER"
                  "database.password" to "DB_PASS"
                  "kafka.bootstrapServers" to "KAFKA_BROKERS"
                  env("OTEL_EXPORTER_OTLP_ENDPOINT", "localhost:$OTLP_PORT")
                  env("KAFKA_LIBRARY", kafkaLibrary)
                  env("STOVE_KAFKA_BRIDGE_PORT", stoveKafkaBridgePortDefault)
                  env("GOCOVERDIR", coverageDirInContainer)
                },
                configureContainer = {
                  withNetworkMode("host")
                  if (hostCoverageDir.isNotBlank()) {
                    withFileSystemBind(hostCoverageDir, COVERAGE_DIR_IN_CONTAINER)
                  }
                }
              )
            }
          }
        }
      }.run()
  }

  override suspend fun afterProject() {
    Stove.stop()
  }
}

private fun resolveAutMode(): GoAutMode =
  when ((System.getProperty("go.aut.mode") ?: System.getenv("GO_AUT_MODE") ?: "process").lowercase()) {
    "process" -> GoAutMode.Process
    "container" -> GoAutMode.Container
    else -> error("Unsupported go.aut.mode. Use 'process' or 'container'.")
  }
