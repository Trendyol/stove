package com.trendyol.stove.testing.e2e.elasticsearch

import arrow.core.Some
import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.trendyol.stove.testing.e2e.database.migrations.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.apache.http.HttpHost
import org.elasticsearch.client.RestClient
import org.junit.jupiter.api.assertThrows
import org.slf4j.*
import java.util.*

const val TEST_INDEX = "stove-test-index"
const val ANOTHER_INDEX = "stove-another-index"

class TestIndexMigrator : DatabaseMigration<ElasticsearchClient> {
  override val order: Int = MigrationPriority.HIGHEST.value
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  override suspend fun execute(connection: ElasticsearchClient) {
    val createIndexRequest: CreateIndexRequest =
      CreateIndexRequest.Builder()
        .index(TEST_INDEX)
        .build()
    connection.indices().create(createIndexRequest)
    logger.info("$TEST_INDEX is created")
  }
}

class AnotherIndexMigrator : DatabaseMigration<ElasticsearchClient> {
  override val order: Int = MigrationPriority.HIGHEST.value + 1
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  override suspend fun execute(connection: ElasticsearchClient) {
    val createIndexRequest: CreateIndexRequest =
      CreateIndexRequest.Builder()
        .index(ANOTHER_INDEX)
        .build()
    connection.indices().create(createIndexRequest)
    logger.info("$ANOTHER_INDEX is created")
  }
}

class Setup : AbstractProjectConfig() {
  override suspend fun beforeProject(): Unit = TestSystem()
    .with {
      elasticsearch {
        ElasticsearchSystemOptions(
          clientConfigurer = ElasticClientConfigurer(
            restClientOverrideFn = Some { cfg -> RestClient.builder(HttpHost(cfg.host, cfg.port)).build() }
          ),
          ElasticContainerOptions(tag = "8.9.0")
        ).migrations {
          register<TestIndexMigrator>()
          register<AnotherIndexMigrator>()
        }
      }
      applicationUnderTest(NoOpApplication())
    }.run()

  override suspend fun afterProject(): Unit = TestSystem.stop()
}

class NoOpApplication : ApplicationUnderTest<Unit> {
  override suspend fun start(configurations: List<String>) = Unit

  override suspend fun stop() = Unit
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
        save(exampleInstance.id, exampleInstance, TEST_INDEX)
        shouldGet<ExampleInstance>(key = exampleInstance.id, index = TEST_INDEX) {
          it.description shouldBe exampleInstance.description
        }
      }
    }
  }

  test("should save and get from another index") {
    val exampleInstance = ExampleInstance("1", "1312")
    TestSystem.validate {
      elasticsearch {
        save(exampleInstance.id, exampleInstance, ANOTHER_INDEX)
        shouldGet<ExampleInstance>(ANOTHER_INDEX, exampleInstance.id) {
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
        save(exampleInstance1.id, exampleInstance1, TEST_INDEX)
        save(exampleInstance2.id, exampleInstance2, TEST_INDEX)
        shouldQuery<ExampleInstance>(queryByDesc._toQuery()) {
          it.size shouldBe 2
        }
        shouldDelete(exampleInstance1.id, TEST_INDEX)
        shouldGet<ExampleInstance>(key = exampleInstance2.id, index = TEST_INDEX) {}
        shouldQuery<ExampleInstance>(queryAsString, TEST_INDEX) {
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
        save(exampleInstance.id, exampleInstance, TEST_INDEX)
        shouldGet<ExampleInstance>(key = exampleInstance.id, index = TEST_INDEX) {
          it.description shouldBe exampleInstance.description
        }

        assertThrows<AssertionError> { shouldNotExist(existDocId, index = TEST_INDEX) }
      }
    }
  }

  test("should does not throw exception when given does not exist id") {
    val notExistDocId = UUID.randomUUID().toString()
    TestSystem.validate {
      elasticsearch {
        shouldNotExist(notExistDocId, index = TEST_INDEX)
      }
    }
  }
})
