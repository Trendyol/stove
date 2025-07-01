package com.stove.spring.standalone.example.e2e

import com.trendyol.stove.testing.e2e.*
import com.trendyol.stove.testing.e2e.couchbase.*
import com.trendyol.stove.testing.e2e.http.*
import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.standalone.kafka.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.wiremock.*
import io.kotest.core.config.AbstractProjectConfig
import org.slf4j.*
import stove.spring.standalone.example.infrastructure.ObjectMapperConfig

class Stove : AbstractProjectConfig() {
  private val logger: Logger = LoggerFactory.getLogger("WireMockMonitor")

  @Suppress("LongMethod")
  override suspend fun beforeProject(): Unit =
    TestSystem()
      .with {
        httpClient {
          HttpClientSystemOptions(
            baseUrl = "http://localhost:8001"
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
        kafka {
          KafkaSystemOptions(
            useEmbeddedKafka = true,
            topicSuffixes = TopicSuffixes().copy(error = listOf(".error", ".DLT", "dlt")),
            serde = StoveSerde.jackson.anyByteArraySerde(ObjectMapperConfig.default),
            containerOptions = KafkaContainerOptions(tag = "7.8.1")
          ) {
            listOf(
              "kafka.bootstrapServers=${it.bootstrapServers}",
              "kafka.isSecure=false",
              "kafka.interceptorClasses=${it.interceptorClass}"
            )
          }
        }
        bridge()
        wiremock {
          WireMockSystemOptions(
            port = 9099,
            removeStubAfterRequestMatched = true,
            afterRequest = { e, _ ->
              logger.info(e.request.toString())
            }
          )
        }
        springBoot(
          runner = { parameters ->
            stove.spring.standalone.example
              .run(parameters)
          },
          withParameters = listOf(
            "server.port=8001",
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
