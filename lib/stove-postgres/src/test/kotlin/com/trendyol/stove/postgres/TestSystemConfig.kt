package com.trendyol.stove.postgres

import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.rdbms.RelationalDatabaseExposedConfiguration
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.ValidationDsl
import com.trendyol.stove.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.system.abstractions.ProvidedRuntime
import com.trendyol.stove.system.stove
import com.trendyol.stove.testCaseConcurrency
import com.trendyol.stove.testSpecConcurrency
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.engine.concurrency.SpecExecutionMode
import io.kotest.engine.concurrency.TestExecutionMode
import org.slf4j.*
import org.testcontainers.postgresql.PostgreSQLContainer

// ============================================================================
// Shared components
// ============================================================================

class NoOpApplication : ApplicationUnderTest<Unit> {
  override suspend fun start(configurations: List<String>) = Unit

  override suspend fun stop() = Unit
}

class InitialMigration : PostgresqlMigration {
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
      configureExposedConfiguration = ::captureSqlConfiguration
    ).migrations {
      register<InitialMigration>()
    }

    Stove()
      .with {
        postgresql { options }
        applicationUnderTest(NoOpApplication())
      }.run()
  }

  override suspend fun stop() {
    com.trendyol.stove.system.Stove
      .stop()
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
        configureExposedConfiguration = ::captureSqlConfiguration
      ).migrations {
        register<InitialMigration>()
      }

    Stove()
      .with {
        postgresql { options }
        applicationUnderTest(NoOpApplication())
      }.run()
  }

  override suspend fun stop() {
    com.trendyol.stove.system.Stove
      .stop()
    externalContainer.stop()
    logger.info("PostgreSQL provided tests completed")
  }
}

// ============================================================================
// Kotest project config - selects strategy based on environment
// ============================================================================

class StoveConfig : AbstractProjectConfig() {
  override val specExecutionMode = SpecExecutionMode.LimitedConcurrency(testSpecConcurrency())
  override val testExecutionMode = TestExecutionMode.LimitedConcurrency(testCaseConcurrency())
  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  private val strategy = PostgresTestStrategy.select()

  override suspend fun beforeProject() = strategy.start()

  override suspend fun afterProject() = strategy.stop()
}

// Written once during project startup, before any concurrent tests run.
private lateinit var sqlConfiguration: RelationalDatabaseExposedConfiguration

private fun captureSqlConfiguration(config: RelationalDatabaseExposedConfiguration): List<String> {
  sqlConfiguration = config
  return emptyList()
}

internal suspend fun ValidationDsl.isolatedPostgresql(scenario: suspend PostgresqlSystem.() -> Unit) {
  val config = sqlConfiguration
  val options = PostgresqlOptions.provided(
    jdbcUrl = config.jdbcUrl,
    host = config.host,
    port = config.port,
    username = config.username,
    password = config.password,
    runMigrations = false,
    configureExposedConfiguration = { emptyList() }
  )
  // Own the connection, retaining the suite's reporter and migrated database.
  // Temporary tables belong to this connection and disappear when it closes.
  PostgresqlSystem(stove, PostgresqlContext(ProvidedRuntime, options)).use { system ->
    system.run()
    scenario(system)
  }
}
