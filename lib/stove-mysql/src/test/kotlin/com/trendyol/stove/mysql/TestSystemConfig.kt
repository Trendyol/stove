@file:Suppress("DEPRECATION")

package com.trendyol.stove.mysql

import com.trendyol.stove.database.migrations.DatabaseMigration
import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.ApplicationUnderTest
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import org.slf4j.*
import org.testcontainers.containers.MySQLContainer

// ============================================================================
// Shared components
// ============================================================================

class NoOpApplication : ApplicationUnderTest<Unit> {
  override suspend fun start(configurations: List<String>) = Unit

  override suspend fun stop() = Unit
}

class InitialMigration : DatabaseMigration<MySqlMigrationContext> {
  private val logger: Logger = LoggerFactory.getLogger(InitialMigration::class.java)

  override val order: Int = 1

  override suspend fun execute(connection: MySqlMigrationContext) {
    logger.info("Executing InitialMigration")
    connection.operations.execute("DROP TABLE IF EXISTS MigrationHistory")
    connection.operations.execute(
      """
      CREATE TABLE IF NOT EXISTS MigrationHistory (
        id INT AUTO_INCREMENT PRIMARY KEY,
        description VARCHAR (50) NOT NULL
      )
      """.trimIndent()
    )
    connection.operations.execute("INSERT INTO MigrationHistory (description) VALUES ('InitialMigration')")
    logger.info("InitialMigration executed")
  }
}

// ============================================================================
// Strategy interface
// ============================================================================

sealed interface MySqlTestStrategy {
  val logger: Logger

  suspend fun start()

  suspend fun stop()

  companion object {
    fun select(): MySqlTestStrategy {
      val useProvided = System.getenv("USE_PROVIDED")?.toBoolean()
        ?: System.getProperty("useProvided")?.toBoolean()
        ?: false

      return if (useProvided) ProvidedMySqlStrategy() else ContainerMySqlStrategy()
    }
  }
}

// ============================================================================
// Container-based strategy (default)
// ============================================================================

class ContainerMySqlStrategy : MySqlTestStrategy {
  override val logger: Logger = LoggerFactory.getLogger(javaClass)

  override suspend fun start() {
    logger.info("Starting MySQL tests with container mode")

    val options = MySqlOptions(
      databaseName = "testing",
      username = "stove",
      password = "Password12!",
      container = MySqlContainerOptions(
        tag = "9.6.0"
      ),
      configureExposedConfiguration = { _ -> listOf() }
    ).migrations {
      register<InitialMigration>()
    }

    Stove()
      .with {
        mysql { options }
        applicationUnderTest(NoOpApplication())
      }.run()
  }

  override suspend fun stop() {
    Stove.stop()
    logger.info("MySQL container tests completed")
  }
}

// ============================================================================
// Provided instance strategy
// ============================================================================

class ProvidedMySqlStrategy : MySqlTestStrategy {
  override val logger: Logger = LoggerFactory.getLogger(javaClass)

  private lateinit var externalContainer: MySQLContainer<*>

  override suspend fun start() {
    logger.info("Starting MySQL tests with provided mode")

    // Start an external container to simulate a provided instance
    externalContainer = MySQLContainer("mysql:9.6.0").apply {
      withDatabaseName("testing")
      withUsername("stove")
      withPassword("Password12!")
      start()
    }

    logger.info("External MySQL container started at ${externalContainer.jdbcUrl}")

    val options = MySqlOptions
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
          sqlOps.execute("DROP TABLE IF EXISTS MigrationHistory")
        },
        configureExposedConfiguration = { _ -> listOf() }
      ).migrations {
        register<InitialMigration>()
      }

    Stove()
      .with {
        mysql { options }
        applicationUnderTest(NoOpApplication())
      }.run()
  }

  override suspend fun stop() {
    Stove.stop()
    externalContainer.stop()
    logger.info("MySQL provided tests completed")
  }
}

// ============================================================================
// Kotest project config - selects strategy based on environment
// ============================================================================

class StoveConfig : AbstractProjectConfig() {
  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  private val strategy = MySqlTestStrategy.select()

  override suspend fun beforeProject() = strategy.start()

  override suspend fun afterProject() = strategy.stop()
}
