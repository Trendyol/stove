package com.trendyol.stove.testing.e2e.database.migrations

import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance

/**
 * The collection that stores the migrations of Database for the application under test
 */
@StoveDsl
class MigrationCollection<TConnection> {
    private val types: MutableMap<KClass<*>, DatabaseMigration<TConnection>> = mutableMapOf()

    /**
     * Register a migration to the collection
     * @param clazz The class of the migration
     */
    @StoveDsl
    fun <T : DatabaseMigration<TConnection>> register(clazz: KClass<T>): MigrationCollection<TConnection> =
        types
            .putIfAbsent(clazz, clazz.createInstance() as DatabaseMigration<TConnection>)
            .let { this }

    /**
     * Register a migration to the collection
     * @param clazz The class of the migration
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
     * Register a migration to the collection
     * @param instance The instance creator of the migration
     */
    @StoveDsl
    inline fun <reified T : DatabaseMigration<TConnection>> register(
        instance: () -> DatabaseMigration<TConnection>
    ): MigrationCollection<TConnection> = this.register(T::class, instance()).let { this }

    /**
     * Replace a migration to the collection
     * @param clazz The class of the migration
     * @param migrator The instance of the migration
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
     * Replace a migration to the collection
     * @param T The class of the migration
     */
    @StoveDsl
    inline fun <reified T : DatabaseMigration<TConnection>> register(): MigrationCollection<TConnection> =
        this.register(T::class).let { this }

    /**
     * Replace a migration to the collection
     */
    @StoveDsl
    inline fun <reified T : DatabaseMigration<TConnection>> replace(
        instance: () -> DatabaseMigration<TConnection>
    ): MigrationCollection<TConnection> = this.replace(T::class, instance()).let { this }

    /**
     * Replace a migration to the collection
     * @param TOld The class of the old migration
     * @param TNew The class of the new migration
     */
    @StoveDsl
    inline fun <
        reified TOld : DatabaseMigration<TConnection>,
        reified TNew : DatabaseMigration<TConnection>
    > replace(): MigrationCollection<TConnection> =
        this.replace(TOld::class, TNew::class.createInstance()).let { this }

    /**
     * Run all the migrations
     * @param connection The connection of the database
     * @return Unit
     */
    @StoveDsl
    suspend fun run(connection: TConnection): Unit = types.map { it.value }.sortedBy { it.order }.forEach { it.execute(connection) }
}
