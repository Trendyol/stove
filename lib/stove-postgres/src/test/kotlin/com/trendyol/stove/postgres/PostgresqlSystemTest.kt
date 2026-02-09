package com.trendyol.stove.postgres

import com.trendyol.stove.system.stove
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotliquery.param

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
      stove {
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
      stove {
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
      stove {
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
      stove {
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

    test("should work with parameterized queries") {
      stove {
        postgresql {
          shouldExecute(
            """
            DROP TABLE IF EXISTS Users;
            CREATE TABLE IF NOT EXISTS Users (
              id serial PRIMARY KEY,
              name VARCHAR (100) NOT NULL,
              age INT NOT NULL,
              email VARCHAR (100) NOT NULL
            );
            """.trimIndent()
          )

          // Insert with parameters
          shouldExecute(
            sql = "INSERT INTO Users (name, age, email) VALUES (?, ?, ?)",
            parameters = listOf("Alice".param(), 30.param(), "alice@example.com".param())
          )

          shouldExecute(
            sql = "INSERT INTO Users (name, age, email) VALUES (?, ?, ?)",
            parameters = listOf("Bob".param(), 25.param(), "bob@example.com".param())
          )

          data class User(
            val id: Long,
            val name: String,
            val age: Int,
            val email: String
          )

          // Query with parameters
          shouldQuery<User>(
            query = "SELECT * FROM Users WHERE age > ? ORDER BY age",
            parameters = listOf(20.param()),
            mapper = { row ->
              User(
                row.long("id"),
                row.string("name"),
                row.int("age"),
                row.string("email")
              )
            }
          ) { actual ->
            actual.size shouldBe 2
            actual.first().name shouldBe "Bob"
            actual.last().name shouldBe "Alice"
          }

          // Query with multiple parameters
          shouldQuery<User>(
            query = "SELECT * FROM Users WHERE name = ? AND age = ?",
            parameters = listOf("Alice".param(), 30.param()),
            mapper = { row ->
              User(
                row.long("id"),
                row.string("name"),
                row.int("age"),
                row.string("email")
              )
            }
          ) { actual ->
            actual.size shouldBe 1
            actual.first().apply {
              name shouldBe "Alice"
              age shouldBe 30
              email shouldBe "alice@example.com"
            }
          }
        }
      }
    }
  })
