package com.trendyol.stove.testing.e2e.elasticsearch

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.trendyol.stove.testing.e2e.database.migrations.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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

class NoOpApplication : ApplicationUnderTest<Unit> {
  override suspend fun start(configurations: List<String>) = Unit

  override suspend fun stop() = Unit
}

class TestIndexMigrator : DatabaseMigration<ElasticsearchSystem> {
  override val order: Int = MigrationPriority.HIGHEST.value
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  override suspend fun execute(connection: ElasticsearchSystem) {
    connection.createIndex(TEST_INDEX)
    logger.info("$TEST_INDEX is created")
  }
}

class AnotherIndexMigrator : DatabaseMigration<ElasticsearchSystem> {
  override val order: Int = MigrationPriority.HIGHEST.value + 1
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  override suspend fun execute(connection: ElasticsearchSystem) {
    connection.createIndex(ANOTHER_INDEX)
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
    logger.info("Starting Elasticsearch tests with container mode")

    val options = ElasticsearchSystemOptions(
      container = ElasticContainerOptions(tag = "8.9.0"),
      configureExposedConfiguration = { _ -> listOf() }
    ).migrations {
      register<TestIndexMigrator>()
      register<AnotherIndexMigrator>()
    }

    TestSystem()
      .with {
        elasticsearch { options }
        applicationUnderTest(NoOpApplication())
      }.run()
  }

  override suspend fun stop() {
    TestSystem.stop()
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
    logger.info("Starting Elasticsearch tests with provided mode")

    // Start an external container to simulate a provided instance
    externalContainer = ElasticsearchContainer(DockerImageName.parse("elasticsearch:8.9.0"))
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
        cleanup = { _ ->
          logger.info("Running cleanup on provided instance")
          // Clean up test data if needed
        },
        configureExposedConfiguration = { _ -> listOf() }
      ).migrations {
        register<TestIndexMigrator>()
        register<AnotherIndexMigrator>()
      }

    TestSystem()
      .with {
        elasticsearch { options }
        applicationUnderTest(NoOpApplication())
      }.run()
  }

  override suspend fun stop() {
    TestSystem.stop()
    externalContainer.stop()
    logger.info("Elasticsearch provided tests completed")
  }
}

// ============================================================================
// Kotest project config - selects strategy based on environment
// ============================================================================

class Stove : AbstractProjectConfig() {
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
      val queryByDesc = """
        {
          "query": {
            "term": {
              "description.keyword": "$desc"
            }
          }
        }
      """.trimIndent()

      TestSystem.validate {
        elasticsearch {
          save(exampleInstance1.id, exampleInstance1, TEST_INDEX)
          save(exampleInstance2.id, exampleInstance2, TEST_INDEX)
          shouldQuery<ExampleInstance>(queryByDesc, TEST_INDEX) {
            it.size shouldBe 2
          }
          shouldDelete(exampleInstance1.id, TEST_INDEX)
          shouldGet<ExampleInstance>(key = exampleInstance2.id, index = TEST_INDEX) {}
          shouldQuery<ExampleInstance>(queryByDesc, TEST_INDEX) {
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
