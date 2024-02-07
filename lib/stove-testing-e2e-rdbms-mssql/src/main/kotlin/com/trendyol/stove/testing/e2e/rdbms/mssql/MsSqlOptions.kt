package com.trendyol.stove.testing.e2e.rdbms.mssql

import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.containers.*
import com.trendyol.stove.testing.e2e.database.migrations.*
import com.trendyol.stove.testing.e2e.rdbms.RelationalDatabaseExposedConfiguration
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.*
import org.testcontainers.containers.MSSQLServerContainer

data class MsSqlOptions(
    val applicationName: String,
    val databaseName: String,
    val userName: String,
    val password: String,
    val registry: String = DEFAULT_REGISTRY,
    override val configureExposedConfiguration: (RelationalDatabaseExposedConfiguration) -> List<String> = { _ -> listOf() }
) : SystemOptions, ConfiguresExposedConfiguration<RelationalDatabaseExposedConfiguration> {
    val migrationCollection: MigrationCollection<SqlMigrationContext> = MigrationCollection()

    /**
     * Helps for registering migrations before the tests run.
     * @see MigrationCollection
     * @see DatabaseMigration
     */
    @StoveDsl
    fun migrations(migration: MigrationCollection<SqlMigrationContext>.() -> Unit): MsSqlOptions =
        migration(
            migrationCollection
        ).let {
            this
        }
}

@StoveDsl
fun WithDsl.mssql(
    configure: () -> MsSqlOptions
): TestSystem = this.testSystem.withMsSql(configure())

@StoveDsl
suspend fun ValidationDsl.mssql(
    validation: suspend MsSqlSystem.() -> Unit
): Unit = validation(this.testSystem.mssql())

internal fun TestSystem.mssql(): MsSqlSystem =
    getOrNone<MsSqlSystem>().getOrElse {
        throw SystemNotRegisteredException(MsSqlSystem::class)
    }

internal fun TestSystem.withMsSql(options: MsSqlOptions): TestSystem =
    withProvidedRegistry(MSSQLServerContainer.IMAGE) {
        MSSQLServerContainer(it)
            .acceptLicense()
            .withEnv("MSSQL_USER", options.userName)
            .withEnv("MSSQL_SA_PASSWORD", options.password)
            .withEnv("MSSQL_DB", options.databaseName)
            .withPassword(options.password)
            .withReuse(this.options.keepDependenciesRunning)
    }.let { getOrRegister(MsSqlSystem(this, MsSqlContext(it, options))) }
        .let { this }
