package com.trendyol.stove.testing.e2e.couchbase

import com.couchbase.client.kotlin.Cluster
import com.trendyol.stove.testing.e2e.couchbase.CouchbaseSystem.Companion.bucket
import com.trendyol.stove.testing.e2e.database.migrations.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import io.kotest.core.config.AbstractProjectConfig
import org.slf4j.*
import org.testcontainers.couchbase.*
import org.testcontainers.utility.DockerImageName
import kotlin.time.Duration.Companion.seconds

// ============================================================================
// Shared components
// ============================================================================

const val TEST_BUCKET = "test-couchbase-bucket"

class NoOpApplication : ApplicationUnderTest<Unit> {
  override suspend fun start(configurations: List<String>) = Unit

  override suspend fun stop() = Unit
}

/**
 * Extended container for testing container customization.
 */
class ExtendedCouchbaseContainer(
  dockerImageName: DockerImageName
) : StoveCouchbaseContainer(dockerImageName) {
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

/**
 * Migration that creates the 'another' collection for testing.
 */
class DefaultMigration : DatabaseMigration<Cluster> {
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  override val order: Int = MigrationPriority.HIGHEST.value

  override suspend fun execute(connection: Cluster) {
    connection
      .bucket(TEST_BUCKET)
      .collections
      .createCollection("_default", "another")

    connection.bucket(TEST_BUCKET).waitUntilReady(30.seconds)
    connection.waitUntilIndexIsCreated(
      "CREATE PRIMARY INDEX ON `${connection.bucket(TEST_BUCKET).name}`.`_default`.`another`",
      30.seconds
    )
    connection.waitForKeySpaceAvailability(TEST_BUCKET, "another", 30.seconds)
    logger.info("default migration is executed")
  }
}

// ============================================================================
// Strategy interface
// ============================================================================

sealed interface CouchbaseTestStrategy {
  val logger: Logger

  suspend fun start()

  suspend fun stop()

  companion object {
    fun select(): CouchbaseTestStrategy {
      val useProvided = System.getenv("USE_PROVIDED")?.toBoolean()
        ?: System.getProperty("useProvided")?.toBoolean()
        ?: false

      return if (useProvided) ProvidedCouchbaseStrategy() else ContainerCouchbaseStrategy()
    }
  }
}

// ============================================================================
// Container-based strategy (default)
// ============================================================================

class ContainerCouchbaseStrategy : CouchbaseTestStrategy {
  override val logger: Logger = LoggerFactory.getLogger(javaClass)

  override suspend fun start() {
    logger.info("Starting Couchbase tests with container mode")

    val options = CouchbaseSystemOptions(
      defaultBucket = TEST_BUCKET,
      configureExposedConfiguration = { _ -> listOf() },
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

    TestSystem {}
      .with {
        couchbase { options }
        applicationUnderTest(NoOpApplication())
      }.run()
  }

  override suspend fun stop() {
    TestSystem.stop()
    logger.info("Couchbase container tests completed")
  }
}

// ============================================================================
// Provided instance strategy
// ============================================================================

class ProvidedCouchbaseStrategy : CouchbaseTestStrategy {
  override val logger: Logger = LoggerFactory.getLogger(javaClass)

  private lateinit var externalContainer: CouchbaseContainer

  override suspend fun start() {
    logger.info("Starting Couchbase tests with provided mode")

    // Start an external container to simulate a provided instance
    externalContainer = CouchbaseContainer(DockerImageName.parse("couchbase/server:7.6.1"))
      .withBucket(BucketDefinition(TEST_BUCKET))
      .withEnabledServices(CouchbaseService.KV, CouchbaseService.INDEX, CouchbaseService.QUERY)
      .apply { start() }

    logger.info("External Couchbase container started at ${externalContainer.connectionString}")

    val options = CouchbaseSystemOptions
      .provided(
        connectionString = externalContainer.connectionString,
        username = externalContainer.username,
        password = externalContainer.password,
        defaultBucket = TEST_BUCKET,
        runMigrations = true,
        cleanup = { cluster ->
          logger.info("Running cleanup on provided instance")
          // Clean up test data if needed
        },
        configureExposedConfiguration = { _ -> listOf() }
      ).migrations {
        register<DefaultMigration>()
      }

    TestSystem {}
      .with {
        couchbase { options }
        applicationUnderTest(NoOpApplication())
      }.run()
  }

  override suspend fun stop() {
    TestSystem.stop()
    externalContainer.stop()
    logger.info("Couchbase provided tests completed")
  }
}

// ============================================================================
// Kotest project config - selects strategy based on environment
// ============================================================================

class Stove : AbstractProjectConfig() {
  private val strategy = CouchbaseTestStrategy.select()

  override suspend fun beforeProject() = strategy.start()

  override suspend fun afterProject() = strategy.stop()
}
