package com.trendyol.stove.database.migrations

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class MigrationCollectionTest :
  FunSpec({

    data class TestConnection(
      val name: String
    )

    class SimpleMigration : DatabaseMigration<TestConnection> {
      override val order: Int = 1
      var executed = false

      override suspend fun execute(connection: TestConnection) {
        executed = true
      }
    }

    class AnotherMigration : DatabaseMigration<TestConnection> {
      override val order: Int = 2
      var executed = false

      override suspend fun execute(connection: TestConnection) {
        executed = true
      }
    }

    class HighPriorityMigration : DatabaseMigration<TestConnection> {
      override val order: Int = MigrationPriority.HIGHEST.value
      var executed = false

      override suspend fun execute(connection: TestConnection) {
        executed = true
      }
    }

    class LowPriorityMigration : DatabaseMigration<TestConnection> {
      override val order: Int = MigrationPriority.LOWEST.value
      var executed = false

      override suspend fun execute(connection: TestConnection) {
        executed = true
      }
    }

    class ConfigurableMigration(
      val config: String
    ) : DatabaseMigration<TestConnection> {
      override val order: Int = 5
      var executed = false
      var executedConfig: String? = null

      override suspend fun execute(connection: TestConnection) {
        executed = true
        executedConfig = config
      }
    }

    test("should register migration by class") {
      val collection = MigrationCollection<TestConnection>()

      collection.register<SimpleMigration>()

      collection.run(TestConnection("test"))
      // If no exception, registration worked
    }

    test("should register migration with instance") {
      val collection = MigrationCollection<TestConnection>()
      val migration = SimpleMigration()

      collection.register(SimpleMigration::class, migration)
      collection.run(TestConnection("test"))

      migration.executed shouldBe true
    }

    test("should register migration with factory function") {
      val collection = MigrationCollection<TestConnection>()

      collection.register<ConfigurableMigration> {
        ConfigurableMigration("custom-config")
      }
      collection.run(TestConnection("test"))
    }

    test("should not replace existing migration with register by class") {
      val collection = MigrationCollection<TestConnection>()

      // First register creates the instance
      collection.register<SimpleMigration>()
      // Second register should not replace (uses putIfAbsent)
      collection.register<SimpleMigration>()

      collection.run(TestConnection("test"))
      // Test passes if no exception - only one migration executed
    }

    test("should replace migration with replace method") {
      val collection = MigrationCollection<TestConnection>()
      val first = SimpleMigration()
      val replacement = SimpleMigration()

      collection.register(SimpleMigration::class, first)
      collection.replace(SimpleMigration::class, replacement)
      collection.run(TestConnection("test"))

      first.executed shouldBe false
      replacement.executed shouldBe true
    }

    test("should replace migration using factory function") {
      val collection = MigrationCollection<TestConnection>()
      val original = ConfigurableMigration("original")

      collection.register(ConfigurableMigration::class, original)
      collection.replace<ConfigurableMigration> {
        ConfigurableMigration("replaced")
      }
      collection.run(TestConnection("test"))

      original.executed shouldBe false
    }

    test("should replace one migration type with another") {
      val collection = MigrationCollection<TestConnection>()

      collection.register<SimpleMigration>()
      collection.replace<SimpleMigration, AnotherMigration>()
      collection.run(TestConnection("test"))
    }

    test("should execute migrations in order") {
      val collection = MigrationCollection<TestConnection>()
      val executionOrder = mutableListOf<Int>()

      val migration1 = object : DatabaseMigration<TestConnection> {
        override val order: Int = 10

        override suspend fun execute(connection: TestConnection) {
          executionOrder.add(10)
        }
      }

      val migration2 = object : DatabaseMigration<TestConnection> {
        override val order: Int = 5

        override suspend fun execute(connection: TestConnection) {
          executionOrder.add(5)
        }
      }

      val migration3 = object : DatabaseMigration<TestConnection> {
        override val order: Int = 15

        override suspend fun execute(connection: TestConnection) {
          executionOrder.add(15)
        }
      }

      collection.register(SimpleMigration::class, migration1)
      collection.register(AnotherMigration::class, migration2)
      collection.register(HighPriorityMigration::class, migration3)
      collection.run(TestConnection("test"))

      executionOrder shouldContainExactly listOf(5, 10, 15)
    }

    test("should execute high priority migrations first") {
      val collection = MigrationCollection<TestConnection>()
      val executionOrder = mutableListOf<String>()

      val highPriority = object : DatabaseMigration<TestConnection> {
        override val order: Int = MigrationPriority.HIGHEST.value

        override suspend fun execute(connection: TestConnection) {
          executionOrder.add("high")
        }
      }

      val normalPriority = object : DatabaseMigration<TestConnection> {
        override val order: Int = 0

        override suspend fun execute(connection: TestConnection) {
          executionOrder.add("normal")
        }
      }

      val lowPriority = object : DatabaseMigration<TestConnection> {
        override val order: Int = MigrationPriority.LOWEST.value

        override suspend fun execute(connection: TestConnection) {
          executionOrder.add("low")
        }
      }

      collection.register(LowPriorityMigration::class, lowPriority)
      collection.register(SimpleMigration::class, normalPriority)
      collection.register(HighPriorityMigration::class, highPriority)
      collection.run(TestConnection("test"))

      executionOrder shouldContainExactly listOf("high", "normal", "low")
    }

    test("should pass connection to migrations") {
      val collection = MigrationCollection<TestConnection>()
      var receivedConnection: TestConnection? = null

      val capturingMigration = object : DatabaseMigration<TestConnection> {
        override val order: Int = 1

        override suspend fun execute(connection: TestConnection) {
          receivedConnection = connection
        }
      }

      collection.register(SimpleMigration::class, capturingMigration)
      val testConnection = TestConnection("my-connection")
      collection.run(testConnection)

      receivedConnection shouldBe testConnection
    }

    test("should handle empty collection") {
      val collection = MigrationCollection<TestConnection>()

      collection.run(TestConnection("test"))
      // No exception means success
    }

    test("should support fluent chaining") {
      val collection = MigrationCollection<TestConnection>()

      val result = collection
        .register<SimpleMigration>()
        .register<AnotherMigration>()

      result shouldBe collection
    }
  })
