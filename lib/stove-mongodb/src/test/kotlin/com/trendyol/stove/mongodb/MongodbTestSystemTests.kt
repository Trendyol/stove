package com.trendyol.stove.mongodb

import com.fasterxml.jackson.annotation.JsonAlias
import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.system.stove
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAny
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import org.bson.codecs.pojo.annotations.*
import org.bson.types.ObjectId
import org.junit.jupiter.api.assertThrows
import org.slf4j.*
import org.testcontainers.mongodb.MongoDBContainer
import org.testcontainers.utility.DockerImageName

// ============================================================================
// Shared components
// ============================================================================

class NoOpApplication : ApplicationUnderTest<Unit> {
  override suspend fun start(configurations: List<String>) = Unit

  override suspend fun stop() = Unit
}

// ============================================================================
// Strategy interface
// ============================================================================

sealed interface MongodbTestStrategy {
  val logger: Logger

  suspend fun start()

  suspend fun stop()

  companion object {
    fun select(): MongodbTestStrategy {
      val useProvided = System.getenv("USE_PROVIDED")?.toBoolean()
        ?: System.getProperty("useProvided")?.toBoolean()
        ?: false

      return if (useProvided) ProvidedMongodbStrategy() else ContainerMongodbStrategy()
    }
  }
}

// ============================================================================
// Container-based strategy (default)
// ============================================================================

class ContainerMongodbStrategy : MongodbTestStrategy {
  override val logger: Logger = LoggerFactory.getLogger(javaClass)

  override suspend fun start() {
    logger.info("Starting MongoDB tests with container mode")

    val options = MongodbSystemOptions {
      listOf()
    }

    Stove()
      .with {
        mongodb { options }
        applicationUnderTest(NoOpApplication())
      }.run()

    // Test pause/unpause functionality
    stove {
      mongodb {
        logger.info("pausing...")
        pause()

        delay(1000)

        logger.info("unpausing...")
        unpause()

        delay(1000)

        logger.info("operating normally...")
        logger.info(inspect().toString())
      }
    }
  }

  override suspend fun stop() {
    com.trendyol.stove.system.Stove
      .stop()
    logger.info("MongoDB container tests completed")
  }
}

// ============================================================================
// Provided instance strategy
// ============================================================================

class ProvidedMongodbStrategy : MongodbTestStrategy {
  override val logger: Logger = LoggerFactory.getLogger(javaClass)

  private lateinit var externalContainer: MongoDBContainer

  override suspend fun start() {
    logger.info("Starting MongoDB tests with provided mode")

    // Start an external container to simulate a provided instance
    externalContainer = MongoDBContainer(DockerImageName.parse("mongo:7.0"))
      .apply { start() }

    logger.info("External MongoDB container started at ${externalContainer.connectionString}")

    val options = MongodbSystemOptions
      .provided(
        connectionString = externalContainer.connectionString,
        host = externalContainer.host,
        port = externalContainer.firstMappedPort,
        runMigrations = true,
        cleanup = { _ ->
          logger.info("Running cleanup on provided instance")
          // Clean up test data if needed
        },
        configureExposedConfiguration = { _ -> listOf() }
      )

    Stove()
      .with {
        mongodb { options }
        applicationUnderTest(NoOpApplication())
      }.run()
  }

  override suspend fun stop() {
    com.trendyol.stove.system.Stove
      .stop()
    externalContainer.stop()
    logger.info("MongoDB provided tests completed")
  }
}

// ============================================================================
// Kotest project config - selects strategy based on environment
// ============================================================================

class StoveConfig : AbstractProjectConfig() {
  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  private val strategy = MongodbTestStrategy.select()

  override suspend fun beforeProject() = strategy.start()

  override suspend fun afterProject() = strategy.stop()
}

// ============================================================================
// Tests
// ============================================================================

class MongodbTestSystemTests :
  FunSpec({
    data class ExampleInstanceWithObjectId(
      @param:BsonId
      @param:JsonAlias("_id")
      val id: ObjectId,
      @param:BsonProperty("aggregateId") val aggregateId: String,
      @param:BsonProperty("description") val description: String
    )

    data class ExampleInstanceWithStringObjectId(
      @param:JsonAlias("_id")
      val id: String,
      @param:BsonProperty("aggregateId") val aggregateId: String,
      @param:BsonProperty("description") val description: String
    )

    test("should save and get with objectId") {
      val id = ObjectId()
      stove {
        mongodb {
          save(
            ExampleInstanceWithObjectId(
              id = id,
              aggregateId = id.toHexString(),
              description = testCase.name.name
            ),
            id.toHexString()
          )
          shouldGet<ExampleInstanceWithObjectId>(id.toHexString()) { actual ->
            actual.aggregateId shouldBe id.toHexString()
            actual.description shouldBe testCase.name.name
          }
        }
      }
    }

    test("should save and get with string objectId") {
      val id = ObjectId()
      stove {
        mongodb {
          save(
            ExampleInstanceWithStringObjectId(
              id = id.toHexString(),
              aggregateId = id.toHexString(),
              description = testCase.name.name
            ),
            id.toHexString()
          )
          shouldGet<ExampleInstanceWithStringObjectId>(id.toHexString()) { actual ->
            actual.aggregateId shouldBe id.toHexString()
            actual.description shouldBe testCase.name.name
          }
        }
      }
    }

    data class ExampleInstanceWithObjectIdForQuery(
      val id: String,
      val description: String
    )
    test("Get with query should work") {
      val id1 = ObjectId()
      val id2 = ObjectId()
      val id3 = ObjectId()
      val firstDesc = "same description"
      val secondDesc = "different description"
      stove {
        mongodb {
          save(
            ExampleInstanceWithObjectId(
              id = id1,
              aggregateId = id1.toHexString(),
              description = firstDesc
            ),
            id1.toHexString()
          )
          save(
            ExampleInstanceWithObjectId(
              id = id2,
              aggregateId = id2.toHexString(),
              description = secondDesc
            ),
            id2.toHexString()
          )
          save(
            ExampleInstanceWithObjectId(
              id = id3,
              aggregateId = id3.toHexString(),
              description = secondDesc
            ),
            id3.toHexString()
          )
          shouldQuery<ExampleInstanceWithObjectIdForQuery>("{\"description\": \"$secondDesc\"}") { actual ->
            actual.count() shouldBe 2
            actual.forAny { it.id shouldBe id2.toHexString() }
            actual.forAny { it.id shouldBe id3.toHexString() }
          }
          shouldQuery<ExampleInstanceWithObjectId>("{\"description\": \"$firstDesc\"}") { actual ->
            actual.count() shouldBe 1
            actual.first().id shouldBe id1
          }
        }
      }
    }

    test("should throw assertion error when document does exist") {
      val id1 = ObjectId()
      stove {
        mongodb {
          save(
            ExampleInstanceWithObjectId(
              id = id1,
              aggregateId = id1.toHexString(),
              description = testCase.name.name + "1"
            ),
            id1.toHexString()
          )
          shouldGet<ExampleInstanceWithStringObjectId>(id1.toHexString()) { actual ->
            actual.aggregateId shouldBe id1.toHexString()
          }
          assertThrows<AssertionError> { shouldNotExist(id1.toHexString()) }
        }
      }
    }

    test("should not throw exception when given does not exist id") {
      val notExistDocId = ObjectId()
      stove {
        mongodb {
          shouldNotExist(notExistDocId.toHexString())
        }
      }
    }

    test("should delete") {
      val id = ObjectId()
      stove {
        mongodb {
          save(
            ExampleInstanceWithObjectId(
              id = id,
              aggregateId = id.toHexString(),
              description = testCase.name.name
            ),
            id.toHexString()
          )
          shouldQuery<ExampleInstanceWithObjectId>("{\"aggregateId\": \"${id.toHexString()}\"}") { actual ->
            actual.size shouldBe 1
          }
          shouldDelete(id.toHexString())
          shouldQuery<ExampleInstanceWithObjectId>("{\"aggregateId\": \"${id.toHexString()}\"}") { actual ->
            actual.size shouldBe 0
          }
        }
      }
    }

    test("complex type") {
      data class Nested(
        val id: String,
        val name: String
      )

      data class ComplexType(
        val id: String,
        val name: String,
        val nested: Nested
      )

      val id = ObjectId()
      val nestedId = ObjectId()
      stove {
        mongodb {
          save(
            ComplexType(
              id = id.toHexString(),
              name = "name",
              nested = Nested(
                id = nestedId.toHexString(),
                name = "nested"
              )
            ),
            id.toHexString()
          )
          shouldGet<ComplexType>(id.toHexString()) { actual ->
            actual.id shouldBe id.toHexString()
            actual.name shouldBe "name"
            actual.nested.id shouldBe actual.nested.id
            actual.nested.name shouldBe "nested"
          }

          shouldQuery<ComplexType>(
            query = "{\"nested.id\": \"${nestedId.toHexString()}\"}"
          ) { actual ->
            actual.size shouldBe 1
            actual.first().id shouldBe id.toHexString()
          }
        }
      }
    }
  })
