package com.trendyol.stove.testing.e2e.rdbms.postgres

import com.trendyol.stove.testing.e2e.system.TestSystem
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/**
 * PostgreSQL system tests that run against both container-based and provided instances.
 *
 * These tests verify:
 * - Basic CRUD operations work correctly
 * - Migrations are executed properly
 * - The same test code works for both container and provided modes
 *
 * To run with provided instance mode:
 * ```
 * ./gradlew :lib:stove-testing-e2e-rdbms-postgres:test -DuseProvided=true
 * ```
 */
class PostgresqlSystemTests :
  FunSpec({

    data class IdAndDescription(
      val id: Long,
      val description: String
    )

    test("migration should create MigrationHistory table") {
      TestSystem.validate {
        postgresql {
          shouldQuery<IdAndDescription>(
            "SELECT * FROM MigrationHistory",
            mapper = { row ->
              IdAndDescription(row.long("id"), row.string("description"))
            }
          ) { actual ->
            actual.size shouldBeGreaterThan 0
            actual.first() shouldBe IdAndDescription(1, "InitialMigration")
          }
        }
      }
    }

    test("should execute DDL and DML statements") {
      TestSystem.validate {
        postgresql {
          shouldExecute(
            """
            DROP TABLE IF EXISTS Dummies;
            CREATE TABLE IF NOT EXISTS Dummies (
              id serial PRIMARY KEY,
              description VARCHAR (50) NOT NULL
            );
            """.trimIndent()
          )
          shouldExecute("INSERT INTO Dummies (description) VALUES ('${testCase.name.name}')")
          shouldQuery<IdAndDescription>(
            "SELECT * FROM Dummies",
            mapper = {
              IdAndDescription(it.long("id"), it.string("description"))
            }
          ) { actual ->
            actual.size shouldBeGreaterThan 0
            actual.first().description shouldBe testCase.name.name
          }
        }
      }
    }

    test("should handle multiple inserts and queries") {
      TestSystem.validate {
        postgresql {
          shouldExecute(
            """
            DROP TABLE IF EXISTS TestItems;
            CREATE TABLE IF NOT EXISTS TestItems (
              id serial PRIMARY KEY,
              name VARCHAR (100) NOT NULL,
              value INT NOT NULL
            );
            """.trimIndent()
          )

          // Insert multiple records
          repeat(5) { i ->
            shouldExecute("INSERT INTO TestItems (name, value) VALUES ('item_$i', $i)")
          }

          // Query and verify
          data class TestItem(
            val id: Long,
            val name: String,
            val value: Int
          )

          shouldQuery<TestItem>(
            "SELECT * FROM TestItems ORDER BY value",
            mapper = { row ->
              TestItem(row.long("id"), row.string("name"), row.int("value"))
            }
          ) { actual ->
            actual.size shouldBe 5
            actual.forEachIndexed { index, item ->
              item.name shouldBe "item_$index"
              item.value shouldBe index
            }
          }
        }
      }
    }

    class NullableIdAndDescription {
      var id: Long? = null
      var description: String? = null
    }

    test("should work with mutable classes") {
      TestSystem.validate {
        postgresql {
          shouldExecute(
            """
            DROP TABLE IF EXISTS Dummies;
            CREATE TABLE IF NOT EXISTS Dummies (
              id serial PRIMARY KEY,
              description VARCHAR (50) NOT NULL
            );
            """.trimIndent()
          )
          shouldExecute("INSERT INTO Dummies (description) VALUES ('${testCase.name.name}')")
          shouldQuery<NullableIdAndDescription>(
            "SELECT * FROM Dummies",
            mapper = { row ->
              val result = NullableIdAndDescription()
              result.id = row.long("id")
              result.description = row.string("description")
              result
            }
          ) { actual ->
            actual.size shouldBeGreaterThan 0
            actual.first().description shouldBe testCase.name.name
          }
        }
      }
    }
  })
