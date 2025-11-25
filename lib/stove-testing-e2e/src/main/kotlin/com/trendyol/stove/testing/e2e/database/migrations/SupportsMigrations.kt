package com.trendyol.stove.testing.e2e.database.migrations

import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl

/**
 * Interface for system options that support migrations.
 *
 * Implement this interface to add migration support to your system options.
 * The [TContext] type parameter represents the context passed to migrations
 * (e.g., database connection, admin client, etc.).
 *
 * Example implementation:
 * ```kotlin
 * class MySystemOptions(
 *   override val configureExposedConfiguration: (MyExposedConfig) -> List<String>
 * ) : SystemOptions, SupportsMigrations<MyMigrationContext, MySystemOptions> {
 *
 *   override val migrationCollection: MigrationCollection<MyMigrationContext> = MigrationCollection()
 * }
 * ```
 *
 * Usage:
 * ```kotlin
 * mySystem {
 *   MySystemOptions(
 *     configureExposedConfiguration = { cfg -> listOf(...) }
 *   ).migrations {
 *     register<MyMigration>()
 *   }
 * }
 * ```
 *
 * @param TContext The type of context passed to migrations (e.g., database connection)
 * @param TSelf The concrete type of the implementing class (for fluent API)
 */
interface SupportsMigrations<TContext, TSelf : SupportsMigrations<TContext, TSelf>> {
  /**
   * The collection of migrations to run.
   */
  val migrationCollection: MigrationCollection<TContext>

  /**
   * Configures migrations for this system.
   *
   * Example:
   * ```kotlin
   * options.migrations {
   *   register<CreateTablesMigration>()
   *   register<SeedDataMigration>()
   * }
   * ```
   *
   * @param migration Configuration block for the migration collection
   * @return This options instance for fluent chaining
   */
  @Suppress("UNCHECKED_CAST")
  @StoveDsl
  fun migrations(
    migration: MigrationCollection<TContext>.() -> Unit
  ): TSelf {
    migration(migrationCollection)
    return this as TSelf
  }
}
