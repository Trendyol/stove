package com.trendyol.stove.testing.e2e.rdbms.postgres

import com.trendyol.stove.testing.e2e.database.migrations.DatabaseMigration
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import io.kotest.core.config.AbstractProjectConfig
import org.slf4j.*
import org.testcontainers.postgresql.PostgreSQLContainer

// ============================================================================
// Shared components
// ============================================================================

class NoOpApplication : ApplicationUnderTest<Unit> {
  override suspend fun start(configurations: List<String>) = Unit

  override suspend fun stop() = Unit
}

class InitialMigration : DatabaseMigration<PostgresSqlMigrationContext> {
  private val logger: Logger = LoggerFactory.getLogger(InitialMigration::class.java)

  override val order: Int = 1

  override suspend fun execute(connection: PostgresSqlMigrationContext) {
    logger.info("Executing InitialMigration")
    connection.operations.execute(
      """
      DROP TABLE IF EXISTS MigrationHistory;
      CREATE TABLE IF NOT EXISTS MigrationHistory (
        id serial PRIMARY KEY,
        description VARCHAR (50) NOT NULL
      );
      INSERT INTO MigrationHistory (description) VALUES ('InitialMigration');
      """.trimIndent()
    )
    logger.info("InitialMigration executed")
  }
}

// ============================================================================
// Strategy interface
// ============================================================================

sealed interface PostgresTestStrategy {
  val logger: Logger

  suspend fun start()

  suspend fun stop()

  companion object {
    fun select(): PostgresTestStrategy {
      val useProvided = System.getenv("USE_PROVIDED")?.toBoolean()
        ?: System.getProperty("useProvided")?.toBoolean()
        ?: false

      return if (useProvided) ProvidedPostgresStrategy() else ContainerPostgresStrategy()
    }
  }
}

// ============================================================================
// Container-based strategy (default)
// ============================================================================

class ContainerPostgresStrategy : PostgresTestStrategy {
  override val logger: Logger = LoggerFactory.getLogger(javaClass)

  override suspend fun start() {
    logger.info("Starting PostgreSQL tests with container mode")

    val options = PostgresqlOptions(
      databaseName = "testing",
      configureExposedConfiguration = { _ -> listOf() }
    ).migrations {
      register<InitialMigration>()
    }

    TestSystem()
      .with {
        postgresql { options }
        applicationUnderTest(NoOpApplication())
      }.run()
  }

  override suspend fun stop() {
    TestSystem.stop()
    logger.info("PostgreSQL container tests completed")
  }
}

// ============================================================================
// Provided instance strategy
// ============================================================================

class ProvidedPostgresStrategy : PostgresTestStrategy {
  override val logger: Logger = LoggerFactory.getLogger(javaClass)

  private lateinit var externalContainer: PostgreSQLContainer

  override suspend fun start() {
    logger.info("Starting PostgreSQL tests with provided mode")

    // Start an external container to simulate a provided instance
    externalContainer = PostgreSQLContainer("postgres:15-alpine").apply {
      withDatabaseName("testing")
      withUsername("postgres")
      withPassword("postgres")
      start()
    }

    logger.info("External PostgreSQL container started at ${externalContainer.jdbcUrl}")

    val options = PostgresqlOptions
      .provided(
        jdbcUrl = externalContainer.jdbcUrl,
        host = externalContainer.host,
        port = externalContainer.firstMappedPort,
        databaseName = "testing",
        username = externalContainer.username,
        password = externalContainer.password,
        runMigrations = true,
        cleanup = { sqlOps ->
          logger.info("Running cleanup on provided instance")
          sqlOps.execute("DROP TABLE IF EXISTS MigrationHistory CASCADE")
        },
        configureExposedConfiguration = { _ -> listOf() }
      ).migrations {
        register<InitialMigration>()
      }

    TestSystem()
      .with {
        postgresql { options }
        applicationUnderTest(NoOpApplication())
      }.run()
  }

  override suspend fun stop() {
    TestSystem.stop()
    externalContainer.stop()
    logger.info("PostgreSQL provided tests completed")
  }
}

// ============================================================================
// Kotest project config - selects strategy based on environment
// ============================================================================

class Stove : AbstractProjectConfig() {
  private val strategy = PostgresTestStrategy.select()

  override suspend fun beforeProject() = strategy.start()

  override suspend fun afterProject() = strategy.stop()
}
