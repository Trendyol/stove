package com.trendyol.stove.cassandra

import com.trendyol.stove.cassandra.CassandraSystem.Companion.session
import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.system.stove
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.slf4j.*
import org.testcontainers.cassandra.CassandraContainer
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

sealed interface CassandraTestStrategy {
  val logger: Logger

  suspend fun start()

  suspend fun stop()

  companion object {
    fun select(): CassandraTestStrategy {
      val useProvided = System.getenv("USE_PROVIDED")?.toBoolean()
        ?: System.getProperty("useProvided")?.toBoolean()
        ?: false

      return if (useProvided) ProvidedCassandraStrategy() else ContainerCassandraStrategy()
    }
  }
}

// ============================================================================
// Container-based strategy (default)
// ============================================================================

class ContainerCassandraStrategy : CassandraTestStrategy {
  override val logger: Logger = LoggerFactory.getLogger(javaClass)

  override suspend fun start() {
    logger.info("Starting Cassandra tests with container mode")

    val options = CassandraSystemOptions(
      keyspace = "stove",
      container = CassandraContainerOptions(),
      configureExposedConfiguration = { _ -> listOf() }
    ).migrations {
      register<CreateKeyspaceMigration>()
    }

    Stove()
      .with {
        cassandra { options }
        applicationUnderTest(NoOpApplication())
      }.run()
  }

  override suspend fun stop() {
    com.trendyol.stove.system.Stove
      .stop()
    logger.info("Cassandra container tests completed")
  }
}

// ============================================================================
// Provided instance strategy
// ============================================================================

class ProvidedCassandraStrategy : CassandraTestStrategy {
  override val logger: Logger = LoggerFactory.getLogger(javaClass)

  private lateinit var externalContainer: CassandraContainer

  override suspend fun start() {
    logger.info("Starting Cassandra tests with provided mode")

    externalContainer = CassandraContainer(DockerImageName.parse("cassandra:4"))
      .apply { start() }

    logger.info("External Cassandra container started at ${externalContainer.host}:${externalContainer.firstMappedPort}")

    val options = CassandraSystemOptions
      .provided(
        host = externalContainer.host,
        port = externalContainer.firstMappedPort,
        datacenter = externalContainer.localDatacenter,
        keyspace = "stove",
        runMigrations = true,
        cleanup = { _ ->
          logger.info("Running cleanup on provided instance")
        },
        configureExposedConfiguration = { _ -> listOf() }
      ).migrations {
        register<CreateKeyspaceMigration>()
      }

    Stove()
      .with {
        cassandra { options }
        applicationUnderTest(NoOpApplication())
      }.run()
  }

  override suspend fun stop() {
    try {
      com.trendyol.stove.system.Stove
        .stop()
    } catch (e: IllegalStateException) {
      logger.warn("Stove.stop() failed (may not have been initialized): ${e.message}")
    }
    if (::externalContainer.isInitialized) {
      externalContainer.stop()
    }
    logger.info("Cassandra provided tests completed")
  }
}

// ============================================================================
// Migrations
// ============================================================================

class CreateKeyspaceMigration : CassandraMigration {
  override val order: Int = 1

  override suspend fun execute(connection: CassandraMigrationContext) {
    connection.session.execute(
      """
      CREATE KEYSPACE IF NOT EXISTS ${connection.options.keyspace}
        WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}
      """.trimIndent()
    )
  }
}

// ============================================================================
// Kotest project config - selects strategy based on environment
// ============================================================================

class StoveConfig : AbstractProjectConfig() {
  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  private val strategy = CassandraTestStrategy.select()

  override suspend fun beforeProject() = strategy.start()

  override suspend fun afterProject() = strategy.stop()
}

// ============================================================================
// Tests
// ============================================================================

class CassandraSystemTests :
  ShouldSpec({

    should("execute a CQL statement without error") {
      stove {
        cassandra {
          shouldExecute("CREATE TABLE IF NOT EXISTS stove.test_table (id uuid PRIMARY KEY, value text)")
        }
      }
    }

    should("query data from Cassandra") {
      stove {
        cassandra {
          shouldExecute("CREATE TABLE IF NOT EXISTS stove.query_test (id uuid PRIMARY KEY, name text)")
          shouldExecute("INSERT INTO stove.query_test (id, name) VALUES (uuid(), 'test-value')")
          shouldQuery("SELECT * FROM stove.query_test") { resultSet ->
            val rows = resultSet.all()
            rows.isNotEmpty() shouldBe true
            rows.first().getString("name") shouldBe "test-value"
          }
        }
      }
    }

    should("use the configured keyspace as the default session keyspace") {
      stove {
        cassandra {
          shouldExecute("CREATE TABLE IF NOT EXISTS default_keyspace_test (id uuid PRIMARY KEY, name text)")
          shouldExecute("INSERT INTO default_keyspace_test (id, name) VALUES (uuid(), 'default-keyspace')")
          shouldQuery("SELECT * FROM default_keyspace_test") { resultSet ->
            val rows = resultSet.all()
            rows.isNotEmpty() shouldBe true
            rows.first().getString("name") shouldBe "default-keyspace"
          }
        }
      }
    }

    should("provide access to the raw CQL session") {
      stove {
        cassandra {
          val result = session().execute("SELECT release_version FROM system.local")
          result.one()?.getString("release_version") shouldNotBe null
        }
      }
    }
  })
