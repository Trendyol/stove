package com.trendyol.stove.testing.e2e.rdbms.mssql

import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.database.migrations.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.slf4j.*

class Setup : AbstractProjectConfig() {
  override suspend fun beforeProject(): Unit =
    TestSystem()
      .with {
        mssql {
          MsSqlOptions(
            applicationName = "test",
            databaseName = "test",
            userName = "sa",
            password = "Password12!",
            container = MssqlContainerOptions(
              toolsPath = ToolsPath.After2019
            ) {
              dockerImageName = "mcr.microsoft.com/mssql/server:2022-latest"
              withStartupAttempts(3)
            },
            configureExposedConfiguration = { _ ->
              listOf()
            }
          ).migrations {
            register<InitialMigration>()
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

class InitialMigration : DatabaseMigration<SqlMigrationContext> {
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

class MssqlSystemTests :
  ShouldSpec({
    should("work") {
      validate {
        mssql {
          ops {
            val result = select("SELECT 1") {
              it.getInt(1)
            }
            result.first() shouldBe 1
          }

          shouldExecute("insert into Person values (1, 'Doe', 'John', '123 Main St', 'Springfield')")

          shouldQuery<Person>(
            query = "select * from Person",
            mapper = {
              Person(
                it.getInt(1),
                it.getString(2),
                it.getString(3),
                it.getString(4),
                it.getString(5)
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
  })
