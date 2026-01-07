package com.trendyol.stove.database.migrations

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

/**
 * Unit tests for the SupportsMigrations interface.
 */
class SupportsMigrationsTest :
  FunSpec({

    /**
     * Simple migration context for testing.
     */
    data class TestMigrationContext(
      val connectionString: String
    )

    /**
     * Test options class implementing SupportsMigrations.
     */
    class TestSystemOptions(
      val name: String
    ) : SupportsMigrations<TestMigrationContext, TestSystemOptions> {
      override val migrationCollection: MigrationCollection<TestMigrationContext> = MigrationCollection()
    }

    /**
     * Test migration that records execution.
     */
    class TestMigration : DatabaseMigration<TestMigrationContext> {
      override val order: Int = 1
      var executed = false
      var executedContext: TestMigrationContext? = null

      override suspend fun execute(connection: TestMigrationContext) {
        executed = true
        executedContext = connection
      }
    }

    /**
     * Another test migration with higher order.
     */
    class TestMigration2 : DatabaseMigration<TestMigrationContext> {
      override val order: Int = 2
      var executed = false

      override suspend fun execute(connection: TestMigrationContext) {
        executed = true
      }
    }

    test("migrations() should return the same instance for fluent chaining") {
      val options = TestSystemOptions("test")
      val result = options.migrations { }

      result shouldBeSameInstanceAs options
    }

    test("migrations() should allow registering migrations") {
      val options = TestSystemOptions("test")
      val migration = TestMigration()

      options.migrations {
        register(TestMigration::class, migration)
      }

      // Run migrations and verify
      val context = TestMigrationContext("test-connection")
      options.migrationCollection.run(context)

      migration.executed shouldBe true
      migration.executedContext shouldBe context
    }

    test("migrations() should allow multiple migrations to be registered") {
      val options = TestSystemOptions("test")
      val migration1 = TestMigration()
      val migration2 = TestMigration2()

      options.migrations {
        register(TestMigration::class, migration1)
        register(TestMigration2::class, migration2)
      }

      // Run migrations
      val context = TestMigrationContext("test-connection")
      options.migrationCollection.run(context)

      migration1.executed shouldBe true
      migration2.executed shouldBe true
    }

    test("migrationCollection should be unique per instance") {
      val options1 = TestSystemOptions("test1")
      val options2 = TestSystemOptions("test2")

      options1.migrationCollection shouldBe options1.migrationCollection
      options1.migrationCollection.hashCode() != options2.migrationCollection.hashCode()
    }

    test("migrations can be chained with other builder methods") {
      class ChainableOptions(
        val name: String,
        var configured: Boolean = false
      ) : SupportsMigrations<TestMigrationContext, ChainableOptions> {
        override val migrationCollection: MigrationCollection<TestMigrationContext> = MigrationCollection()

        fun configure(): ChainableOptions {
          configured = true
          return this
        }
      }

      val options = ChainableOptions("test")
        .configure()
        .migrations {
          register<TestMigration>()
        }

      options.configured shouldBe true
      options.name shouldBe "test"
    }
  })
