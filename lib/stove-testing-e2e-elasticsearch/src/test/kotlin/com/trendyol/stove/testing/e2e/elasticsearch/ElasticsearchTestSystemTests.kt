package com.trendyol.stove.testing.e2e.elasticsearch

import arrow.core.Some
import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.trendyol.stove.testing.e2e.database.migrations.DatabaseMigration
import com.trendyol.stove.testing.e2e.elasticsearch.ElasticsearchSystem.Companion.shouldGet
import com.trendyol.stove.testing.e2e.elasticsearch.ElasticsearchSystem.Companion.shouldQuery
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.testing.e2e.system.abstractions.ExperimentalStoveDsl
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.apache.http.HttpHost
import org.elasticsearch.client.RestClient
import org.junit.jupiter.api.assertThrows
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*

const val testIndex = "stove-test-index"
const val anotherIndex = "stove-another-index"

class TestIndexMigrator : DatabaseMigration<ElasticsearchClient> {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)
    override suspend fun execute(connection: ElasticsearchClient) {
        val createIndexRequest: CreateIndexRequest = CreateIndexRequest.Builder()
            .index(testIndex)
            .build()
        connection.indices().create(createIndexRequest)
        logger.info("$testIndex is created")
    }
}

class AnotherIndexMigrator : DatabaseMigration<ElasticsearchClient> {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)
    override suspend fun execute(connection: ElasticsearchClient) {
        val createIndexRequest: CreateIndexRequest = CreateIndexRequest.Builder()
            .index(anotherIndex)
            .build()
        connection.indices().create(createIndexRequest)
        logger.info("$anotherIndex is created")
    }
}

@ExperimentalStoveDsl
class Setup : AbstractProjectConfig() {
    override suspend fun beforeProject(): Unit = TestSystem()
        .with {
            elasticsearch {
                ElasticsearchSystemOptions(
                    DefaultIndex(index = testIndex, migrator = TestIndexMigrator()),
                    clientConfigurer = ElasticClientConfigurer(
                        restClientOverrideFn = Some { cfg ->
                            RestClient.builder(HttpHost(cfg.host, cfg.port)).build()
                        }
                    )
                ).migrations { register<AnotherIndexMigrator>() }
            }
            applicationUnderTest(NoOpApplication())
        }.run()

    override suspend fun afterProject(): Unit = TestSystem.stop()
}

class NoOpApplication : ApplicationUnderTest<Unit> {
    override suspend fun start(configurations: List<String>) {
    }

    override suspend fun stop() {
    }
}

class ElasticsearchTestSystemTests : FunSpec({

    @JsonIgnoreProperties
    data class ExampleInstance(
        val id: String,
        val description: String
    )
    test("should save and get") {
        val exampleInstance = ExampleInstance("1", "1312")
        TestSystem.validate {
            elasticsearch {
                save(exampleInstance.id, exampleInstance)
                shouldGet<ExampleInstance>(exampleInstance.id) {
                    it.description shouldBe exampleInstance.description
                }
            }
        }
    }

    test("should save and get from another index") {
        val exampleInstance = ExampleInstance("1", "1312")
        TestSystem.validate {
            elasticsearch {
                save(exampleInstance.id, exampleInstance, anotherIndex)
                shouldGet<ExampleInstance>(exampleInstance.id, anotherIndex) {
                    it.description shouldBe exampleInstance.description
                }
            }
        }
    }

    test("should save 2 documents with the same description, then delete first one and query by description") {
        val desc = "some description"
        val exampleInstance1 = ExampleInstance("1", desc)
        val exampleInstance2 = ExampleInstance("2", desc)
        val queryByDesc = QueryBuilders.term().field("description.keyword").value(desc).queryName("query_name").build()
        val queryAsString = queryByDesc.asJsonString()
        TestSystem.validate {
            elasticsearch {
                save(exampleInstance1.id, exampleInstance1)
                save(exampleInstance2.id, exampleInstance2)
                shouldQuery<ExampleInstance>(queryByDesc._toQuery()) {
                    it.size shouldBe 2
                }
                shouldDelete(exampleInstance1.id)
                shouldGet<ExampleInstance>(exampleInstance2.id) {}
                shouldQuery<ExampleInstance>(queryAsString) {
                    it.size shouldBe 1
                }
            }
        }
    }

    test("should throw assertion error when document does exist") {
        val existDocId = UUID.randomUUID().toString()
        val exampleInstance = ExampleInstance(existDocId, "1312")
        TestSystem.validate {
            elasticsearch {
                save(exampleInstance.id, exampleInstance)
                shouldGet<ExampleInstance>(exampleInstance.id) {
                    it.description shouldBe exampleInstance.description
                }

                assertThrows<AssertionError> { shouldNotExist(existDocId) }
            }
        }
    }

    test("should does not throw exception when given does not exist id") {
        val notExistDocId = UUID.randomUUID().toString()
        TestSystem.validate {
            elasticsearch {
                shouldNotExist(notExistDocId)
            }
        }
    }
})
