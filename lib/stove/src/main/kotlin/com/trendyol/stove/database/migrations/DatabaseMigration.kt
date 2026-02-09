package com.trendyol.stove.database.migrations

import com.trendyol.stove.system.abstractions.AfterRunAware

/**
 * Interface for database schema migrations and test data setup.
 *
 * Migrations run after the database container starts and before tests execute.
 * Use migrations for:
 * - Creating database schemas and tables
 * - Setting up indexes
 * - Seeding reference data
 * - Any setup that requires a running database
 *
 * ## Module-Specific Type Aliases
 *
 * Each Stove module provides a convenience type alias so you don't need to
 * remember the generic `DatabaseMigration<XyzContext>` form:
 *
 * | Module          | Type Alias                | Resolves To                                    |
 * |-----------------|---------------------------|------------------------------------------------|
 * | stove-postgres  | `PostgresqlMigration`     | `DatabaseMigration<PostgresSqlMigrationContext>`|
 * | stove-mysql     | `MySqlMigration`          | `DatabaseMigration<MySqlMigrationContext>`      |
 * | stove-mssql     | `MsSqlMigration`          | `DatabaseMigration<SqlMigrationContext>`        |
 * | stove-mongodb   | `MongodbMigration`        | `DatabaseMigration<MongodbMigrationContext>`    |
 * | stove-couchbase | `CouchbaseMigration`      | `DatabaseMigration<Cluster>`                   |
 * | stove-elasticsearch | `ElasticsearchMigration` | `DatabaseMigration<ElasticsearchClient>`    |
 * | stove-redis     | `RedisMigration`          | `DatabaseMigration<RedisMigrationContext>`      |
 * | stove-kafka     | `KafkaMigration`          | `DatabaseMigration<KafkaMigrationContext>`      |
 *
 * ## Creating a Migration
 *
 * ```kotlin
 * // Using the module-specific type alias (recommended):
 * class CreateUsersTableMigration : PostgresqlMigration {
 *     override val order: Int = MigrationPriority.HIGHEST.value
 *
 *     override suspend fun execute(connection: PostgresSqlMigrationContext) {
 *         connection.operations.execute("""
 *             CREATE TABLE IF NOT EXISTS users (
 *                 id SERIAL PRIMARY KEY,
 *                 name VARCHAR(255) NOT NULL,
 *                 email VARCHAR(255) UNIQUE NOT NULL
 *             )
 *         """)
 *     }
 * }
 *
 * // Or using the generic interface directly:
 * class SeedTestDataMigration : DatabaseMigration<PostgresSqlMigrationContext> {
 *     override val order: Int = 100  // Run after schema creation
 *
 *     override suspend fun execute(connection: PostgresSqlMigrationContext) {
 *         connection.operations.execute(
 *             "INSERT INTO users (name, email) VALUES ('Test User', 'test@example.com')"
 *         )
 *     }
 * }
 * ```
 *
 * ## Registering Migrations
 *
 * ```kotlin
 * postgresql {
 *     PostgresqlOptions(
 *         configureExposedConfiguration = { /* ... */ }
 *     ).migrations {
 *         register<CreateUsersTableMigration>()
 *         register<CreateOrdersTableMigration>()
 *         register<SeedTestDataMigration>()
 *     }
 * }
 * ```
 *
 * ## Migration Order
 *
 * Migrations execute in ascending order of the [order] property:
 * - Use [MigrationPriority.HIGHEST] for schema creation
 * - Use [MigrationPriority.LOWEST] for cleanup or final setup
 * - Use intermediate values (e.g., 1, 2, 3, 100) for ordered execution
 *
 * ## Important Notes
 *
 * - Migrations cannot have constructor dependencies (use `object` or no-arg constructors)
 * - Migrations run after [AfterRunAware.afterRun]
 * - Connection is managed by Stove - don't close it manually
 * - Use idempotent statements (`IF NOT EXISTS`) for safety
 *
 * @param TConnection The database connection type (e.g., `Connection` for JDBC, `MongoClient` for MongoDB)
 * @see MigrationPriority
 * @see AfterRunAware.afterRun
 */
interface DatabaseMigration<in TConnection> {
  /**
   * Executes the migration using the provided connection.
   *
   * The [connection] is already established and ready for use.
   * Do not close or dispose the connection - Stove manages its lifecycle.
   *
   * @param connection An active database connection.
   */
  suspend fun execute(connection: TConnection)

  /**
   * The execution order of this migration.
   *
   * Lower values execute first. Use [MigrationPriority] constants
   * or specific integer values for fine-grained control.
   *
   * @see MigrationPriority
   */
  val order: Int
}

/**
 * Predefined priority values for migration ordering.
 *
 * ## Usage
 *
 * ```kotlin
 * class SchemaCreation : PostgresqlMigration {
 *     override val order = MigrationPriority.HIGHEST.value  // Runs first
 *     // ...
 * }
 *
 * class DataSeeding : PostgresqlMigration {
 *     override val order = MigrationPriority.LOWEST.value   // Runs last
 *     // ...
 * }
 *
 * class MiddleMigration : PostgresqlMigration {
 *     override val order = 50  // Custom priority
 *     // ...
 * }
 * ```
 */
enum class MigrationPriority(
  /**
   * The integer value representing this priority level.
   */
  val value: Int
) {
  /**
   * Lowest priority - migration runs last.
   *
   * Use for cleanup, finalization, or dependent migrations.
   */
  LOWEST(Int.MAX_VALUE),

  /**
   * Highest priority - migration runs first.
   *
   * Use for schema creation, essential setup, or migrations
   * that others depend on.
   */
  HIGHEST(Int.MIN_VALUE)
}
