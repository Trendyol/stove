package com.trendyol.stove.database.migrations

import com.trendyol.stove.system.annotations.StoveDsl
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance

/**
 * A registry for database migrations that manages registration, ordering, and execution.
 *
 * This class stores and executes [DatabaseMigration]s in the correct order.
 * Migrations are deduplicated by class type and executed sorted by their [DatabaseMigration.order].
 *
 * ## Registering Migrations
 *
 * ```kotlin
 * postgresql {
 *     PostgresqlOptions(
 *         configureExposedConfiguration = { /* ... */ }
 *     ).migrations {
 *         // Simple registration (uses no-arg constructor)
 *         register<CreateUsersTableMigration>()
 *         register<CreateOrdersTableMigration>()
 *
 *         // Registration with custom instance
 *         register<SeedDataMigration> {
 *             SeedDataMigration(testDataPath = "/test-data.sql")
 *         }
 *     }
 * }
 * ```
 *
 * ## Replacing Migrations
 *
 * Useful for test-specific migrations that override default behavior:
 *
 * ```kotlin
 * .migrations {
 *     register<ProductionSeedMigration>()
 *
 *     // Replace with test-specific seed data
 *     replace<ProductionSeedMigration, TestSeedMigration>()
 *
 *     // Or replace with custom instance
 *     replace<ProductionSeedMigration> {
 *         MinimalSeedMigration()
 *     }
 * }
 * ```
 *
 * ## Execution Order
 *
 * Migrations execute in ascending order of [DatabaseMigration.order]:
 *
 * ```kotlin
 * class SchemaCreation : DatabaseMigration<Connection> {
 *     override val order = MigrationPriority.HIGHEST.value  // -2147483648
 * }
 *
 * class DataSeeding : DatabaseMigration<Connection> {
 *     override val order = 100  // After schema
 * }
 *
 * class IndexCreation : DatabaseMigration<Connection> {
 *     override val order = MigrationPriority.LOWEST.value   // 2147483647
 * }
 * ```
 *
 * @param TConnection The database connection type (e.g., `Connection`, `MongoClient`).
 * @see DatabaseMigration
 * @see MigrationPriority
 */
@StoveDsl
class MigrationCollection<TConnection> {
  private val types: MutableMap<KClass<*>, DatabaseMigration<TConnection>> = mutableMapOf()

  /**
   * Registers a migration by its class, creating an instance using reflection.
   *
   * The migration class must have a no-argument constructor.
   * If a migration of this type is already registered, it won't be replaced.
   *
   * @param clazz The migration class to register.
   * @return This collection for fluent chaining.
   */
  @StoveDsl
  fun <T : DatabaseMigration<TConnection>> register(clazz: KClass<T>): MigrationCollection<TConnection> =
    types
      .putIfAbsent(clazz, clazz.createInstance() as DatabaseMigration<TConnection>)
      .let { this }

  /**
   * Registers a migration with a specific instance.
   *
   * Use this when your migration requires constructor parameters
   * or custom initialization.
   *
   * @param clazz The migration class (used as the registry key).
   * @param migrator The migration instance to register.
   * @return This collection for fluent chaining.
   */
  @StoveDsl
  fun <T : DatabaseMigration<TConnection>> register(
    clazz: KClass<T>,
    migrator: DatabaseMigration<TConnection>
  ): MigrationCollection<TConnection> =
    types
      .put(clazz, migrator)
      .let { this }

  /**
   * Registers a migration using a factory function.
   *
   * ```kotlin
   * register<ConfigurableMigration> {
   *     ConfigurableMigration(batchSize = 1000)
   * }
   * ```
   *
   * @param instance Factory function that creates the migration instance.
   * @return This collection for fluent chaining.
   */
  @StoveDsl
  inline fun <reified T : DatabaseMigration<TConnection>> register(
    instance: () -> DatabaseMigration<TConnection>
  ): MigrationCollection<TConnection> = this.register(T::class, instance()).let { this }

  /**
   * Replaces an existing migration with a new instance.
   *
   * @param clazz The migration class to replace (registry key).
   * @param migrator The new migration instance.
   * @return This collection for fluent chaining.
   */
  @StoveDsl
  fun <T : DatabaseMigration<TConnection>> replace(
    clazz: KClass<T>,
    migrator: DatabaseMigration<TConnection>
  ): MigrationCollection<TConnection> =
    types
      .replace(clazz, migrator)
      .let { this }

  /**
   * Registers a migration using its reified type parameter.
   *
   * This is the most common way to register migrations:
   *
   * ```kotlin
   * migrations {
   *     register<CreateTablesMigration>()
   *     register<SeedDataMigration>()
   * }
   * ```
   *
   * @return This collection for fluent chaining.
   */
  @StoveDsl
  inline fun <reified T : DatabaseMigration<TConnection>> register(): MigrationCollection<TConnection> =
    this.register(T::class).let { this }

  /**
   * Replaces an existing migration using a factory function.
   *
   * ```kotlin
   * replace<ProductionMigration> {
   *     TestMigration()
   * }
   * ```
   *
   * @param instance Factory function that creates the replacement migration.
   * @return This collection for fluent chaining.
   */
  @StoveDsl
  inline fun <reified T : DatabaseMigration<TConnection>> replace(
    instance: () -> DatabaseMigration<TConnection>
  ): MigrationCollection<TConnection> = this.replace(T::class, instance()).let { this }

  /**
   * Replaces one migration type with another.
   *
   * The new migration class must have a no-argument constructor.
   *
   * ```kotlin
   * // Replace production migration with test-specific one
   * replace<ProductionSeedMigration, TestSeedMigration>()
   * ```
   *
   * @param TOld The migration type to replace.
   * @param TNew The new migration type.
   * @return This collection for fluent chaining.
   */
  @StoveDsl
  inline fun <
    reified TOld : DatabaseMigration<TConnection>,
    reified TNew : DatabaseMigration<TConnection>
  > replace(): MigrationCollection<TConnection> =
    this.replace(TOld::class, TNew::class.createInstance()).let { this }

  /**
   * Executes all registered migrations in order.
   *
   * Migrations are sorted by [DatabaseMigration.order] (ascending)
   * and executed sequentially.
   *
   * @param connection The active database connection for executing migrations.
   */
  @StoveDsl
  suspend fun run(connection: TConnection): Unit = types
    .map {
      it.value
    }.sortedBy {
      it.order
    }.forEach { it.execute(connection) }
}
