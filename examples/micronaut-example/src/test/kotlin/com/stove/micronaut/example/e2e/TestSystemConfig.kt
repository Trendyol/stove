package com.stove.micronaut.example.e2e

import com.trendyol.stove.testing.*
import com.trendyol.stove.testing.e2e.couchbase.*
import com.trendyol.stove.testing.e2e.http.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.wiremock.*
import io.kotest.core.config.AbstractProjectConfig
import org.slf4j.*
import stove.micronaut.example.infrastructure.couchbase.ObjectMapperConfig

class TestSystemConfig : AbstractProjectConfig() {
  private val logger: Logger = LoggerFactory.getLogger("WireMockMonitor")

  @Suppress("LongMethod")
  override suspend fun beforeProject() {
    TestSystem()
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
            port = 7078,
            removeStubAfterRequestMatched = true,
            afterRequest = { e, _ ->
              logger.info(e.request.toString())
            }
          )
        }
        micronaut(
          runner = { parameters ->
            stove.micronaut.example.run(parameters) {
            }
          },
          withParameters = listOf(
            "server.port=8080",
            "logging.level.root=info",
            "logging.level.org.springframework.web=info"
          )
        )
      }.run()
  }

  override suspend fun afterProject(): Unit = TestSystem.stop()
}
