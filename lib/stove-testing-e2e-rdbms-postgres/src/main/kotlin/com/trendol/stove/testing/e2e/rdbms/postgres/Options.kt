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

data class PostgresqlContainerOptions(
    override val registry: String = DEFAULT_REGISTRY,
    override val image: String = DEFAULT_POSTGRES_IMAGE_NAME,
    override val tag: String = "latest",
    override val compatibleSubstitute: String? = null,
    override val containerFn: ContainerFn<PostgreSQLContainer<*>> = {}
) : ContainerOptions

@StoveDsl
data class PostgresqlOptions(
    val databaseName: String = "stove-e2e-testing",
    val container: PostgresqlContainerOptions = PostgresqlContainerOptions(),
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
    withProvidedRegistry(options.container.imageWithTag, options.container.registry, options.container.compatibleSubstitute) {
        PostgreSQLContainer(it)
            .withDatabaseName(options.databaseName)
            .withUsername("sa")
            .withPassword("sa")
            .withReuse(this.options.keepDependenciesRunning)
            .apply(options.container.containerFn)
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
