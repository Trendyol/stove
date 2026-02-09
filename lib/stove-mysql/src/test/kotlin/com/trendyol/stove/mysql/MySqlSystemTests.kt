package com.trendyol.stove.mysql

import com.trendyol.stove.system.stove
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotliquery.param

/**
 * MySQL system tests that run against both container-based and provided instances.
 */
class MySqlSystemTests :
  FunSpec({
    data class IdAndDescription(
      val id: Long,
      val description: String
    )

    test("migration should create MigrationHistory table") {
      stove {
        mysql {
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
        mysql {
          shouldExecute("DROP TABLE IF EXISTS Dummies")
          shouldExecute(
            """
            CREATE TABLE IF NOT EXISTS Dummies (
              id INT AUTO_INCREMENT PRIMARY KEY,
              description VARCHAR (50) NOT NULL
            )
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
        mysql {
          shouldExecute("DROP TABLE IF EXISTS TestItems")
          shouldExecute(
            """
            CREATE TABLE IF NOT EXISTS TestItems (
              id INT AUTO_INCREMENT PRIMARY KEY,
              name VARCHAR (100) NOT NULL,
              value INT NOT NULL
            )
            """.trimIndent()
          )

          repeat(5) { i ->
            shouldExecute("INSERT INTO TestItems (name, value) VALUES ('item_$i', $i)")
          }

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
        mysql {
          shouldExecute("DROP TABLE IF EXISTS Dummies")
          shouldExecute(
            """
            CREATE TABLE IF NOT EXISTS Dummies (
              id INT AUTO_INCREMENT PRIMARY KEY,
              description VARCHAR (50) NOT NULL
            )
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
        mysql {
          shouldExecute("DROP TABLE IF EXISTS Products")
          shouldExecute(
            """
            CREATE TABLE IF NOT EXISTS Products (
              id INT AUTO_INCREMENT PRIMARY KEY,
              name VARCHAR (100) NOT NULL,
              price DECIMAL(10,2) NOT NULL,
              category VARCHAR (50) NOT NULL
            )
            """.trimIndent()
          )

          // Insert with parameters
          shouldExecute(
            sql = "INSERT INTO Products (name, price, category) VALUES (?, ?, ?)",
            parameters = listOf("Laptop".param(), 999.99.param(), "Electronics".param())
          )

          shouldExecute(
            sql = "INSERT INTO Products (name, price, category) VALUES (?, ?, ?)",
            parameters = listOf("Mouse".param(), 29.99.param(), "Electronics".param())
          )

          shouldExecute(
            sql = "INSERT INTO Products (name, price, category) VALUES (?, ?, ?)",
            parameters = listOf("Desk".param(), 299.99.param(), "Furniture".param())
          )

          data class Product(
            val id: Long,
            val name: String,
            val price: Double,
            val category: String
          )

          // Query with parameters
          shouldQuery<Product>(
            query = "SELECT * FROM Products WHERE category = ? ORDER BY price",
            parameters = listOf("Electronics".param()),
            mapper = { row ->
              Product(
                row.long("id"),
                row.string("name"),
                row.double("price"),
                row.string("category")
              )
            }
          ) { actual ->
            actual.size shouldBe 2
            actual.first().name shouldBe "Mouse"
            actual.last().name shouldBe "Laptop"
          }

          // Query with multiple parameters
          shouldQuery<Product>(
            query = "SELECT * FROM Products WHERE category = ? AND price > ?",
            parameters = listOf("Electronics".param(), 50.0.param()),
            mapper = { row ->
              Product(
                row.long("id"),
                row.string("name"),
                row.double("price"),
                row.string("category")
              )
            }
          ) { actual ->
            actual.size shouldBe 1
            actual.first().apply {
              name shouldBe "Laptop"
              price shouldBe 999.99
              category shouldBe "Electronics"
            }
          }
        }
      }
    }
  })
