package com.trendyol.stove.testing.e2e.rdbms.postgres

import com.trendol.stove.testing.e2e.rdbms.postgres.*
import com.trendyol.stove.testing.e2e.database.migrations.DatabaseMigration
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.slf4j.*

class Setup : AbstractProjectConfig() {
  override suspend fun beforeProject(): Unit =
    TestSystem()
      .with {
        postgresql {
          PostgresqlOptions(
            databaseName = "testing",
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

class InitialMigration : DatabaseMigration<PostgresSqlMigrationContext> {
  private val logger: Logger = LoggerFactory.getLogger(InitialMigration::class.java)

  override suspend fun execute(connection: PostgresSqlMigrationContext) {
    logger.info("Executing InitialMigration")
    connection.operations.execute(
      """
                    DROP TABLE IF EXISTS MigrationHistory;
                    CREATE TABLE IF NOT EXISTS MigrationHistory (
                    	id serial PRIMARY KEY,
                    	description VARCHAR (50)  NOT NULL
                    );
                    insert into MigrationHistory (description) values ('InitialMigration');
      """.trimIndent()
    )

    logger.info("InitialMigration executed")
  }

  override val order: Int = 1
}

class NoOpApplication : ApplicationUnderTest<Unit> {
  override suspend fun start(configurations: List<String>) = Unit

  override suspend fun stop() = Unit
}

class PostgresqlSystemTests :
  FunSpec({

    data class IdAndDescription(
      val id: Long,
      val description: String
    )

    test("initial migration should work") {
      TestSystem.validate {
        postgresql {
          shouldQuery<IdAndDescription>(
            "SELECT * FROM MigrationHistory",
            mapper = { row ->
              IdAndDescription(row.getLong("id"), row.getString("description"))
            }
          ) { actual ->
            actual.size shouldBeGreaterThan 0
            actual.first() shouldBe IdAndDescription(1, "InitialMigration")
          }
        }
      }
    }

    test("should save and get with immutable data class") {
      TestSystem.validate {
        postgresql {
          shouldExecute(
            """
                    DROP TABLE IF EXISTS Dummies;                    
                    CREATE TABLE IF NOT EXISTS Dummies (
                    	id serial PRIMARY KEY,
                    	description VARCHAR (50)  NOT NULL
                    );
            """.trimIndent()
          )
          shouldExecute("INSERT INTO Dummies (description) VALUES ('${testCase.name.testName}')")
          shouldQuery<IdAndDescription>("SELECT * FROM Dummies", mapper = {
            IdAndDescription(it.getLong("id"), it.getString("description"))
          }) { actual ->
            actual.size shouldBeGreaterThan 0
            actual.first().description shouldBe testCase.name.testName
          }
        }
      }
    }

    class NullableIdAndDescription {
      var id: Long? = null
      var description: String? = null
    }

    test("should save and get with mutable class") {
      TestSystem.validate {
        postgresql {
          shouldExecute(
            """
                    DROP TABLE IF EXISTS Dummies;
                    CREATE TABLE IF NOT EXISTS Dummies (
                    	id serial PRIMARY KEY,
                    	description VARCHAR (50)  NOT NULL
                    );
            """.trimIndent()
          )
          shouldExecute("INSERT INTO Dummies (description) VALUES ('${testCase.name.testName}')")
          shouldQuery<NullableIdAndDescription>(
            "SELECT * FROM Dummies",
            mapper = { row ->
              val result = NullableIdAndDescription()
              result.id = row.getLong("id")
              result.description = row.getString("description")
              result
            }
          ) { actual ->
            actual.size shouldBeGreaterThan 0
            actual.first().description shouldBe testCase.name.testName
          }
        }
      }
    }
  })
