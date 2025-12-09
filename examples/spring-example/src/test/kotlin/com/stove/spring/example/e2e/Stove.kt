package com.stove.spring.example.e2e

import com.trendyol.stove.testing.e2e.*
import com.trendyol.stove.testing.e2e.couchbase.*
import com.trendyol.stove.testing.e2e.http.*
import com.trendyol.stove.testing.e2e.kafka.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.wiremock.*
import io.kotest.core.config.AbstractProjectConfig
import org.slf4j.*

class Stove : AbstractProjectConfig() {
  private val logger: Logger = LoggerFactory.getLogger("WireMockMonitor")

  @Suppress("LongMethod")
  override suspend fun beforeProject(): Unit =
    TestSystem()
      .with {
        httpClient {
          HttpClientSystemOptions(
            baseUrl = "http://localhost:8004"
          )
        }
        couchbase {
          CouchbaseSystemOptions(
            "Stove",
            containerOptions = CouchbaseContainerOptions(tag = "7.6.1") {
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
        kafka {
          KafkaSystemOptions(
            containerOptions = KafkaContainerOptions(tag = "7.8.1"),
            configureExposedConfiguration = {
              listOf(
                "kafka.bootstrapServers=${it.bootstrapServers}",
                "kafka.isSecure=false"
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
        springBoot(
          runner = { parameters ->
            stove.spring.example.run(parameters) {
              this.addTestSystemDependencies()
            }
          },
          withParameters = listOf(
            "server.port=8004",
            "logging.level.root=info",
            "logging.level.org.springframework.web=info",
            "spring.profiles.active=default",
            "kafka.heartbeatInSeconds=2",
            "kafka.autoCreateTopics=true",
            "kafka.offset=earliest",
            "kafka.secureKafka=false"
          )
        )
      }.run()

  override suspend fun afterProject(): Unit = TestSystem.stop()
}
