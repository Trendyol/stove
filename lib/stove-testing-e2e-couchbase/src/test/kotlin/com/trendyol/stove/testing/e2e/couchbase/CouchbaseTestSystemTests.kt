package com.trendyol.stove.testing.e2e.couchbase

import com.couchbase.client.core.error.DocumentNotFoundException
import com.couchbase.client.kotlin.Cluster
import com.trendyol.stove.testing.e2e.couchbase.CouchbaseSystem.Companion.bucket
import com.trendyol.stove.testing.e2e.database.migrations.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.assertThrows
import org.slf4j.*
import org.testcontainers.couchbase.CouchbaseService
import org.testcontainers.utility.DockerImageName
import java.util.*
import kotlin.time.Duration.Companion.seconds

const val TEST_BUCKET = "test-couchbase-bucket"

class ExtendedCouchbaseContainer(dockerImageName: DockerImageName) : StoveCouchbaseContainer(dockerImageName) {
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  override fun start() {
    logger.info("starting extended couchbase container")
    super.start()
  }

  override fun stop() {
    logger.info("stopping extended couchbase container")
    super.stop()
  }
}

class Setup : AbstractProjectConfig() {
  override suspend fun beforeProject(): Unit =
    TestSystem {}.with {
      couchbase {
        CouchbaseSystemOptions(
          defaultBucket = TEST_BUCKET,
          configureExposedConfiguration = { _ ->
            listOf()
          },
          containerOptions = CouchbaseContainerOptions(
            useContainerFn = { ExtendedCouchbaseContainer(it) },
            tag = "7.6.1"
          ) {
            withStartupAttempts(3)
            withEnabledServices(CouchbaseService.KV, CouchbaseService.INDEX, CouchbaseService.QUERY)
          }
        ).migrations {
          register<DefaultMigration>()
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

class DefaultMigration : DatabaseMigration<Cluster> {
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  override val order: Int = MigrationPriority.HIGHEST.value

  override suspend fun execute(connection: Cluster) {
    connection
      .bucket(TEST_BUCKET)
      .collections.createCollection("_default", "another")

    connection.bucket(TEST_BUCKET).waitUntilReady(30.seconds)
    connection.waitUntilIndexIsCreated("CREATE PRIMARY INDEX ON `${connection.bucket(TEST_BUCKET).name}`.`_default`.`another`", 30.seconds)
    connection.waitForKeySpaceAvailability(TEST_BUCKET, "another", 30.seconds)
    logger.info("default migration is executed")
  }
}

class CouchbaseTestSystemUsesDslTests : FunSpec({

  data class ExampleInstance(
    val id: String,
    val description: String
  )

  test("should save and get") {
    val id = UUID.randomUUID().toString()
    val anotherCollectionName = "another"
    validate {
      couchbase {
        saveToDefaultCollection(id, ExampleInstance(id = id, description = testCase.name.testName))
        save(anotherCollectionName, id = id, ExampleInstance(id = id, description = testCase.name.testName))
        shouldGet<ExampleInstance>(id) { actual ->
          actual.id shouldBe id
          actual.description shouldBe testCase.name.testName
        }
        shouldGet<ExampleInstance>(anotherCollectionName, id) { actual ->
          actual.id shouldBe id
          actual.description shouldBe testCase.name.testName
        }
      }
    }
  }

  test("should not get when document does not exist") {
    val id = UUID.randomUUID().toString()
    val notExistDocId = UUID.randomUUID().toString()
    validate {
      couchbase {
        saveToDefaultCollection(id, ExampleInstance(id = id, description = testCase.name.testName))
        shouldGet<ExampleInstance>(id) { actual ->
          actual.id shouldBe id
          actual.description shouldBe testCase.name.testName
        }
        shouldNotExist(notExistDocId)
      }
    }
  }

  test("should throw assertion exception when document exist") {
    val id = UUID.randomUUID().toString()
    validate {
      couchbase {
        saveToDefaultCollection(id, ExampleInstance(id = id, description = testCase.name.testName))
        shouldGet<ExampleInstance>(id) { actual ->
          actual.id shouldBe id
          actual.description shouldBe testCase.name.testName
        }
        assertThrows<AssertionError> { shouldNotExist(id) }
      }
    }
  }

  test("should delete") {
    val id = UUID.randomUUID().toString()
    validate {
      couchbase {
        saveToDefaultCollection(id, ExampleInstance(id = id, description = testCase.name.testName))
        shouldGet<ExampleInstance>(id) { actual ->
          actual.id shouldBe id
          actual.description shouldBe testCase.name.testName
        }
        shouldDelete(id)
        shouldNotExist(id)
      }
    }
  }

  test("should delete from another collection") {
    val id = UUID.randomUUID().toString()
    val anotherCollectionName = "another"
    validate {
      couchbase {
        save(anotherCollectionName, id = id, ExampleInstance(id = id, description = testCase.name.testName))
        shouldGet<ExampleInstance>(anotherCollectionName, id) { actual ->
          actual.id shouldBe id
          actual.description shouldBe testCase.name.testName
        }
        shouldDelete(anotherCollectionName, id)
        shouldNotExist(anotherCollectionName, id)
      }
    }
  }

  test("should not delete when document does not exist") {
    val id = UUID.randomUUID().toString()
    validate {
      couchbase {
        shouldNotExist(id)
        assertThrows<DocumentNotFoundException> { shouldDelete(id) }
      }
    }
  }

  test("should not delete from another collection when document does not exist") {
    val id = UUID.randomUUID().toString()
    val anotherCollectionName = "another"
    validate {
      couchbase {
        shouldNotExist(anotherCollectionName, id)
        assertThrows<DocumentNotFoundException> { shouldDelete(anotherCollectionName, id) }
      }
    }
  }

  test("should query") {
    val id = UUID.randomUUID().toString()
    val id2 = UUID.randomUUID().toString()
    validate {
      couchbase {
        saveToDefaultCollection(id, ExampleInstance(id = id, description = testCase.name.testName))
        saveToDefaultCollection(id2, ExampleInstance(id = id2, description = testCase.name.testName))
        shouldQuery<ExampleInstance>(
          "SELECT c.id, c.* FROM `${this.bucket().name}`.`${this.collection.scope.name}`.`${this.collection.name}` c"
        ) { result ->
          result.size shouldBeGreaterThanOrEqual 2
          result.contains(ExampleInstance(id = id, description = testCase.name.testName)) shouldBe true
          result.contains(ExampleInstance(id = id2, description = testCase.name.testName)) shouldBe true
        }
      }
    }
  }
})
