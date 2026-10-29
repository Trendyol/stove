package com.trendyol.stove.mssql

import com.trendyol.stove.database.migrations.*
import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.functional.*
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
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.engine.concurrency.SpecExecutionMode
import io.kotest.engine.concurrency.TestExecutionMode
import io.kotest.matchers.shouldBe
import kotliquery.param
import org.slf4j.*
import org.testcontainers.mssqlserver.MSSQLServerContainer
import org.testcontainers.utility.DockerImageName

// ============================================================================
// Shared components
// ============================================================================

class NoOpApplication : ApplicationUnderTest<Unit> {
  override suspend fun start(configurations: List<String>) = Unit

  override suspend fun stop() = Unit
}

class InitialMigration : MsSqlMigration {
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  override val order: Int = MigrationPriority.HIGHEST.value + 1

  override suspend fun execute(connection: SqlMigrationContext) {
    val sql = """
     CREATE TABLE Person (
        PersonID int,
        LastName varchar(255),
        FirstName varchar(255),
        Address varchar(255),
        City varchar(255)
      );
    """.trimIndent()
    logger.info("Executing migration: $sql")
    Try {
      connection.executeAsRoot(sql)
    }.recover {
      logger.error("Migration failed", it)
      throw it
    }
    logger.info("Migration executed successfully")
  }
}

data class Person(
  val personId: Int,
  val lastName: String,
  val firstName: String,
  val address: String,
  val city: String
)

// ============================================================================
// Strategy interface
// ============================================================================

sealed interface MsSqlTestStrategy {
  val logger: Logger

  suspend fun start()

  suspend fun stop()

  companion object {
    fun select(): MsSqlTestStrategy {
      val useProvided = System.getenv("USE_PROVIDED")?.toBoolean()
        ?: System.getProperty("useProvided")?.toBoolean()
        ?: false

      return if (useProvided) ProvidedMsSqlStrategy() else ContainerMsSqlStrategy()
    }
  }
}

// ============================================================================
// Container-based strategy (default)
// ============================================================================

class ContainerMsSqlStrategy : MsSqlTestStrategy {
  override val logger: Logger = LoggerFactory.getLogger(javaClass)

  override suspend fun start() {
    logger.info("Starting MSSQL tests with container mode")

    val options = MsSqlOptions(
      applicationName = "test",
      databaseName = "test",
      userName = "sa",
      password = "Password12!",
      container = MssqlContainerOptions(
        toolsPath = ToolsPath.After2019,
        image = "mcr.microsoft.com/mssql/server",
        tag = "2022-CU16-ubuntu-22.04"
      ) {
        withStartupAttempts(3)
      },
      configureExposedConfiguration = ::captureSqlConfiguration
    ).migrations {
      register<InitialMigration>()
    }

    Stove()
      .with {
        mssql { options }
        applicationUnderTest(NoOpApplication())
      }.run()
  }

  override suspend fun stop() {
    com.trendyol.stove.system.Stove
      .stop()
    logger.info("MSSQL container tests completed")
  }
}

// ============================================================================
// Provided instance strategy
// ============================================================================

class ProvidedMsSqlStrategy : MsSqlTestStrategy {
  override val logger: Logger = LoggerFactory.getLogger(javaClass)

  private lateinit var externalContainer: MSSQLServerContainer

  override suspend fun start() {
    logger.info("Starting MSSQL tests with provided mode")

    // Start an external container to simulate a provided instance
    externalContainer = MSSQLServerContainer(
      DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-CU16-ubuntu-22.04")
    ).apply {
      acceptLicense()
      withPassword("Password12!")
      start()
    }

    logger.info("External MSSQL container started at ${externalContainer.jdbcUrl}")

    val options = MsSqlOptions
      .provided(
        jdbcUrl = externalContainer.jdbcUrl,
        host = externalContainer.host,
        port = externalContainer.firstMappedPort,
        applicationName = "test",
        databaseName = "master", // Use master for provided since we can't easily create DB
        userName = externalContainer.username,
        password = externalContainer.password,
        runMigrations = true,
        cleanup = { sqlOps ->
          logger.info("Running cleanup on provided instance")
          Try { sqlOps.execute("DROP TABLE IF EXISTS Person") }
        },
        configureExposedConfiguration = ::captureSqlConfiguration
      ).migrations {
        register<InitialMigration>()
      }

    Stove()
      .with {
        mssql { options }
        applicationUnderTest(NoOpApplication())
      }.run()
  }

  override suspend fun stop() {
    com.trendyol.stove.system.Stove
      .stop()
    externalContainer.stop()
    logger.info("MSSQL provided tests completed")
  }
}

// ============================================================================
// Kotest project config - selects strategy based on environment
// ============================================================================

class StoveConfig : AbstractProjectConfig() {
  override val specExecutionMode = SpecExecutionMode.LimitedConcurrency(testSpecConcurrency())
  override val testExecutionMode = TestExecutionMode.LimitedConcurrency(testCaseConcurrency())
  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  private val strategy = MsSqlTestStrategy.select()

  override suspend fun beforeProject() = strategy.start()

  override suspend fun afterProject() = strategy.stop()
}

// ============================================================================
// Tests
// ============================================================================

class MssqlSystemTests :
  ShouldSpec({
    blockingTest = true
    should("work") {
      stove {
        isolatedMssql { personTable ->
          ops {
            val result = select("SELECT 1") {
              it.int(1)
            }
            result.first() shouldBe 1
          }

          shouldExecute("insert into $personTable values (1, 'Doe', 'John', '123 Main St', 'Springfield')")

          shouldQuery<Person>(
            query = "select * from $personTable",
            mapper = {
              Person(
                it.int(1),
                it.string(2),
                it.string(3),
                it.string(4),
                it.string(5)
              )
            }
          ) { result ->
            result.size shouldBe 1
            result.first().apply {
              personId shouldBe 1
              lastName shouldBe "Doe"
              firstName shouldBe "John"
              address shouldBe "123 Main St"
              city shouldBe "Springfield"
            }
          }
        }
      }
    }

    should("work with parameterized queries") {
      stove {
        isolatedMssql { personTable ->
          // Insert with parameters
          shouldExecute(
            sql = "insert into $personTable values (?, ?, ?, ?, ?)",
            parameters = listOf(
              2.param(),
              "Smith".param(),
              "Jane".param(),
              "456 Oak Ave".param(),
              "Boston".param()
            )
          )

          shouldExecute(
            sql = "insert into $personTable values (?, ?, ?, ?, ?)",
            parameters = listOf(
              3.param(),
              "Johnson".param(),
              "Mike".param(),
              "789 Pine Rd".param(),
              "Boston".param()
            )
          )

          // Query with parameters
          shouldQuery<Person>(
            query = "select * from $personTable where City = ? order by PersonID",
            parameters = listOf("Boston".param()),
            mapper = {
              Person(
                it.int(1),
                it.string(2),
                it.string(3),
                it.string(4),
                it.string(5)
              )
            }
          ) { result ->
            result.size shouldBe 2
            result.first().apply {
              personId shouldBe 2
              lastName shouldBe "Smith"
              firstName shouldBe "Jane"
              city shouldBe "Boston"
            }
            result.last().apply {
              personId shouldBe 3
              lastName shouldBe "Johnson"
              firstName shouldBe "Mike"
              city shouldBe "Boston"
            }
          }

          // Query with multiple parameters
          shouldQuery<Person>(
            query = "select * from $personTable where LastName = ? and FirstName = ?",
            parameters = listOf("Smith".param(), "Jane".param()),
            mapper = {
              Person(
                it.int(1),
                it.string(2),
                it.string(3),
                it.string(4),
                it.string(5)
              )
            }
          ) { result ->
            result.size shouldBe 1
            result.first().apply {
              lastName shouldBe "Smith"
              firstName shouldBe "Jane"
              address shouldBe "456 Oak Ave"
            }
          }
        }
      }
    }
  })

// Written once during project startup, before any concurrent tests run.
private lateinit var sqlConfiguration: RelationalDatabaseExposedConfiguration

private fun captureSqlConfiguration(config: RelationalDatabaseExposedConfiguration): List<String> {
  sqlConfiguration = config
  return emptyList()
}

internal suspend fun ValidationDsl.isolatedMssql(scenario: suspend MsSqlSystem.(String) -> Unit) {
  val config = sqlConfiguration
  val options = MsSqlOptions.provided(
    jdbcUrl = config.jdbcUrl, host = config.host, port = config.port,
    applicationName = "test", databaseName = "test", userName = config.username,
    password = config.password, runMigrations = false,
    configureExposedConfiguration = { emptyList() }
  )
  // Own the connection, retaining the suite's reporter and migrated database.
  // SQL Server temp tables created by prepared statements do not outlive that statement.
  val personTable = "Person_" + java.util.UUID.randomUUID().toString().replace("-", "")
  MsSqlSystem(stove, MsSqlContext(ProvidedRuntime, options)).use { system ->
    system.run()
    system.shouldExecute("SELECT TOP 0 * INTO $personTable FROM Person")
    try {
      scenario(system, personTable)
    } finally {
      system.shouldExecute("DROP TABLE $personTable")
    }
  }
}
