package com.stove.micronaut.example.e2e

import com.trendyol.stove.couchbase.*
import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.http.*
import com.trendyol.stove.micronaut.*
import com.trendyol.stove.system.Stove
import com.trendyol.stove.wiremock.*
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import org.slf4j.*
import stove.micronaut.example.run as runMicronautApp

class StoveConfig : AbstractProjectConfig() {
  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  private val logger: Logger = LoggerFactory.getLogger("WireMockMonitor")

  @Suppress("LongMethod")
  override suspend fun beforeProject() {
    Stove()
      .with {
        httpClient {
          HttpClientSystemOptions(
            baseUrl = "http://localhost:8080"
          )
        }
        couchbase {
          CouchbaseSystemOptions(
            "Stove",
            containerOptions = CouchbaseContainerOptions(tag = "latest") {
              withStartupAttempts(3)
            },
            configureExposedConfiguration = { cfg ->
              listOf(
                "couchbase.hosts=${cfg.hostsWithPort}",
                "couchbase.username=${cfg.username}",
                "couchbase.password=${cfg.password}"
              )
            }
          )
        }
        bridge()
        wiremock {
          WireMockSystemOptions(
            port = 7079,
            removeStubAfterRequestMatched = true,
            afterRequest = { e, _ ->
              logger.info(e.request.toString())
            }
          )
        }
        micronaut(
          runner = { parameters ->
            runMicronautApp(parameters) {
            }
          },
          withParameters = listOf(
            "server.port=8080",
            "logging.level.root=info",
            "logging.level.org.micronaut.web=info"
          )
        )
      }.run()
  }

  override suspend fun afterProject(): Unit = Stove.stop()
}
