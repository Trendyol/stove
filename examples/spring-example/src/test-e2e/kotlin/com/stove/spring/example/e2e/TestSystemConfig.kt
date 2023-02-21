package com.stove.spring.example.e2e

import com.trendyol.stove.testing.e2e.couchbase.CouchbaseSystemOptions
import com.trendyol.stove.testing.e2e.couchbase.withCouchbase
import com.trendyol.stove.testing.e2e.elasticsearch.DefaultIndex
import com.trendyol.stove.testing.e2e.elasticsearch.ElasticsearchSystemOptions
import com.trendyol.stove.testing.e2e.elasticsearch.withElasticsearch
import com.trendyol.stove.testing.e2e.http.withHttpClient
import com.trendyol.stove.testing.e2e.kafka.withKafka
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.systemUnderTest
import com.trendyol.stove.testing.e2e.wiremock.WireMockSystemOptions
import com.trendyol.stove.testing.e2e.wiremock.withWireMock
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.listeners.AfterTestListener
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class TestSystemConfig : AbstractProjectConfig() {

    private val logger: Logger = LoggerFactory.getLogger("WireMockMonitor")
    override suspend fun beforeProject(): Unit =
        TestSystem(baseUrl = "http://localhost:8001")
            .withHttpClient()
            .withCouchbase(
                CouchbaseSystemOptions("Stove")
            )
            .withKafka()
            .withWireMock(
                port = 9090,
                WireMockSystemOptions(
                    removeStubAfterRequestMatched = true,
                    afterRequest = { e, _, _ ->
                        logger.info(e.request.toString())
                    }
                )
            ).withElasticsearch(
                ElasticsearchSystemOptions(
                    defaultIndex = DefaultIndex("some"),
                    configureExposedConfiguration = { cfg ->
                        listOf("elasticsearch.certificateBytes=${cfg.certificate.bytes}")
                    }
                )
            )
            .systemUnderTest(
                runner = { parameters ->
                    stove.spring.example.run(parameters) { this.addTestSystemDependencies() }
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
            .run()

    override fun extensions(): List<Extension> {
        val listener =
            object : AfterTestListener {
                override suspend fun afterTest(
                    testCase: TestCase,
                    result: TestResult,
                ) {
                    // TestSystem.instance.wiremock().validate()
                }
            }

        return listOf(listener)
    }

    override suspend fun afterProject() {
        TestSystem.instance.close()
    }
}
