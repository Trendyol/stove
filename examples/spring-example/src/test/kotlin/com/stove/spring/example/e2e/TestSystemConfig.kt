package com.stove.spring.example.e2e

import com.trendyol.stove.testing.e2e.bridge
import com.trendyol.stove.testing.e2e.couchbase.CouchbaseSystemOptions
import com.trendyol.stove.testing.e2e.couchbase.couchbase
import com.trendyol.stove.testing.e2e.http.httpClient
import com.trendyol.stove.testing.e2e.kafka.KafkaContainerOptions
import com.trendyol.stove.testing.e2e.kafka.KafkaSystemOptions
import com.trendyol.stove.testing.e2e.kafka.kafka
import com.trendyol.stove.testing.e2e.springBoot
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.wiremock.WireMockSystemOptions
import com.trendyol.stove.testing.e2e.wiremock.wiremock
import io.kotest.core.config.AbstractProjectConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class TestSystemConfig : AbstractProjectConfig() {
    private val logger: Logger = LoggerFactory.getLogger("WireMockMonitor")

    override suspend fun beforeProject(): Unit =
        TestSystem(baseUrl = "http://localhost:8001")
            .with {
                httpClient()
                couchbase { CouchbaseSystemOptions("Stove") }
                kafka {
                    KafkaSystemOptions(containerOptions = KafkaContainerOptions(tag = "latest"))
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
                        stove.spring.example.run(parameters) {
                            this.addTestSystemDependencies()
                        }
                    },
                    withParameters =
                        listOf(
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
