package com.trendol.stove.testing.e2e.rdbms.postgres

import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.containers.*
import com.trendyol.stove.testing.e2e.database.migrations.MigrationCollection
import com.trendyol.stove.testing.e2e.rdbms.*
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import org.testcontainers.containers.PostgreSQLContainer

const val DEFAULT_POSTGRES_IMAGE_NAME = "postgres"

@StoveDsl
data class PostgresqlOptions(
    val databaseName: String = "stove-e2e-testing",
    val registry: String = DEFAULT_REGISTRY,
    val imageName: String = DEFAULT_POSTGRES_IMAGE_NAME,
    override val configureExposedConfiguration: (RelationalDatabaseExposedConfiguration) -> List<String> = { _ -> listOf() }
) : SystemOptions, ConfiguresExposedConfiguration<RelationalDatabaseExposedConfiguration> {
    val migrationCollection: MigrationCollection<PostgresSqlMigrationContext> = MigrationCollection()

    @StoveDsl
    fun migrations(migration: MigrationCollection<PostgresSqlMigrationContext>.() -> Unit): PostgresqlOptions =
        migration(
            migrationCollection
        ).let {
            this
        }
}

internal class PostgresqlContext(
    container: PostgreSQLContainer<*>,
    val options: PostgresqlOptions
) : RelationalDatabaseContext<PostgreSQLContainer<*>>(container, options.configureExposedConfiguration)

@StoveDsl
data class PostgresSqlMigrationContext(
    val options: PostgresqlOptions,
    val operations: SqlOperations,
    val executeAsRoot: suspend (String) -> Unit
)

internal fun TestSystem.withPostgresql(options: PostgresqlOptions = PostgresqlOptions()): TestSystem =
    withProvidedRegistry(options.imageName, options.registry, "postgres") {
        PostgreSQLContainer(it)
            .withDatabaseName(options.databaseName)
            .withUsername("sa")
            .withPassword("sa")
            .withReuse(this.options.keepDependenciesRunning)
    }.let { getOrRegister(PostgresqlSystem(this, PostgresqlContext(it, options))) }
        .let { this }

internal fun TestSystem.postgresql(): PostgresqlSystem =
    getOrNone<PostgresqlSystem>().getOrElse {
        throw SystemNotRegisteredException(PostgresqlSystem::class)
    }

@StoveDsl
fun WithDsl.postgresql(configure: () -> PostgresqlOptions = { PostgresqlOptions() }): TestSystem =
    this.testSystem.withPostgresql(configure())

@StoveDsl
suspend fun ValidationDsl.postgresql(validation: @PostgresDsl suspend PostgresqlSystem.() -> Unit): Unit =
    validation(this.testSystem.postgresql())
