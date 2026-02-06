package com.trendyol.stove.elasticsearch

import arrow.core.Some
import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.trendyol.stove.database.migrations.*
import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.system.stove
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.apache.http.HttpHost
import org.elasticsearch.client.RestClient
import org.junit.jupiter.api.assertThrows
import org.slf4j.*
import org.testcontainers.elasticsearch.ElasticsearchContainer
import org.testcontainers.utility.DockerImageName
import java.util.*

// ============================================================================
// Shared components
// ============================================================================

const val TEST_INDEX = "stove-test-index"
const val ANOTHER_INDEX = "stove-another-index"
const val DEFAULT_ELASTICSEARCH_TEST_TAG = "8.9.0"

object ElasticsearchTestRuntimeConfig {
  val tag: String =
    System.getenv("ELASTICSEARCH_TEST_TAG")
      ?: System.getProperty("elasticsearchTestTag")
      ?: DEFAULT_ELASTICSEARCH_TEST_TAG

  val imageName: DockerImageName = DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:$tag")
}

class NoOpApplication : ApplicationUnderTest<Unit> {
  override suspend fun start(configurations: List<String>) = Unit

  override suspend fun stop() = Unit
}

class TestIndexMigrator : DatabaseMigration<ElasticsearchClient> {
  override val order: Int = MigrationPriority.HIGHEST.value
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  override suspend fun execute(connection: ElasticsearchClient) {
    val createIndexRequest: CreateIndexRequest =
      CreateIndexRequest
        .Builder()
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
      CreateIndexRequest
        .Builder()
        .index(ANOTHER_INDEX)
        .build()
    connection.indices().create(createIndexRequest)
    logger.info("$ANOTHER_INDEX is created")
  }
}

// ============================================================================
// Strategy interface
// ============================================================================

sealed interface ElasticsearchTestStrategy {
  val logger: Logger

  suspend fun start()

  suspend fun stop()

  companion object {
    fun select(): ElasticsearchTestStrategy {
      val useProvided = System.getenv("USE_PROVIDED")?.toBoolean()
        ?: System.getProperty("useProvided")?.toBoolean()
        ?: false

      return if (useProvided) ProvidedElasticsearchStrategy() else ContainerElasticsearchStrategy()
    }
  }
}

// ============================================================================
// Container-based strategy (default)
// ============================================================================

class ContainerElasticsearchStrategy : ElasticsearchTestStrategy {
  override val logger: Logger = LoggerFactory.getLogger(javaClass)

  override suspend fun start() {
    logger.info("Starting Elasticsearch tests with container mode. tag=${ElasticsearchTestRuntimeConfig.tag}")

    val options = ElasticsearchSystemOptions(
      clientConfigurer = ElasticClientConfigurer(
        restClientOverrideFn = Some { cfg -> RestClient.builder(HttpHost(cfg.host, cfg.port)).build() }
      ),
      ElasticContainerOptions(tag = ElasticsearchTestRuntimeConfig.tag),
      configureExposedConfiguration = { _ -> listOf() }
    ).migrations {
      register<TestIndexMigrator>()
      register<AnotherIndexMigrator>()
    }

    Stove()
      .with {
        elasticsearch { options }
        applicationUnderTest(NoOpApplication())
      }.run()
  }

  override suspend fun stop() {
    com.trendyol.stove.system.Stove
      .stop()
    logger.info("Elasticsearch container tests completed")
  }
}

// ============================================================================
// Provided instance strategy
// ============================================================================

class ProvidedElasticsearchStrategy : ElasticsearchTestStrategy {
  override val logger: Logger = LoggerFactory.getLogger(javaClass)

  private lateinit var externalContainer: ElasticsearchContainer

  override suspend fun start() {
    logger.info("Starting Elasticsearch tests with provided mode. tag=${ElasticsearchTestRuntimeConfig.tag}")

    // Start an external container to simulate a provided instance
    externalContainer = ElasticsearchContainer(ElasticsearchTestRuntimeConfig.imageName)
      .withEnv("xpack.security.enabled", "false")
      .withEnv("discovery.type", "single-node")
      .apply { start() }

    logger.info("External Elasticsearch container started at ${externalContainer.httpHostAddress}")

    val hostPort = externalContainer.httpHostAddress.split(":")
    val options = ElasticsearchSystemOptions
      .provided(
        host = hostPort[0],
        port = hostPort[1].toInt(),
        runMigrations = true,
        clientConfigurer = ElasticClientConfigurer(
          restClientOverrideFn = Some { cfg -> RestClient.builder(HttpHost(cfg.host, cfg.port)).build() }
        ),
        cleanup = { client ->
          logger.info("Running cleanup on provided instance")
          // Clean up test data if needed
        },
        configureExposedConfiguration = { _ -> listOf() }
      ).migrations {
        register<TestIndexMigrator>()
        register<AnotherIndexMigrator>()
      }

    Stove()
      .with {
        elasticsearch { options }
        applicationUnderTest(NoOpApplication())
      }.run()
  }

  override suspend fun stop() {
    com.trendyol.stove.system.Stove
      .stop()
    externalContainer.stop()
    logger.info("Elasticsearch provided tests completed")
  }
}

// ============================================================================
// Kotest project config - selects strategy based on environment
// ============================================================================

class StoveConfig : AbstractProjectConfig() {
  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  private val strategy = ElasticsearchTestStrategy.select()

  override suspend fun beforeProject() = strategy.start()

  override suspend fun afterProject() = strategy.stop()
}

// ============================================================================
// Tests
// ============================================================================

class ElasticsearchTestSystemTests :
  FunSpec({

    @JsonIgnoreProperties
    data class ExampleInstance(
      val id: String,
      val description: String
    )
    test("should save and get") {
      val exampleInstance = ExampleInstance("1", "1312")
      stove {
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
      stove {
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
      val queryByDesc = QueryBuilders
        .term()
        .field("description.keyword")
        .value(desc)
        .queryName("query_name")
        .build()
      val queryAsString = queryByDesc.asJsonString()
      stove {
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
      stove {
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
      stove {
        elasticsearch {
          shouldNotExist(notExistDocId, index = TEST_INDEX)
        }
      }
    }
  })
