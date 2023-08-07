package com.trendyol.stove.testing.e2e.database.migrations

import com.trendyol.stove.testing.e2e.system.abstractions.AfterRunAware

/**
 * Interface for writing migrations, and operations that necessary for testing.
 * All the migrations will run after the database instance run successfully.
 *
 * Migrations can be more than one.
 *
 * Migrations can not have **constructor dependencies.**
 * @see AfterRunAware.afterRun
 */
interface DatabaseMigration<in TConnection> {

    /**
     * [connection] is ready for executing operations
     */
    suspend fun execute(connection: TConnection)

    val order: Int
}

enum class MigrationPriority(val value: Int) {
    LOWEST(Int.MAX_VALUE),
    HIGHEST(Int.MIN_VALUE)
}
