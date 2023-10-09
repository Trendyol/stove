package com.trendol.stove.testing.e2e.rdbms.postgres

import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.containers.DEFAULT_REGISTRY
import com.trendyol.stove.testing.e2e.containers.withProvidedRegistry
import com.trendyol.stove.testing.e2e.rdbms.RelationalDatabaseContext
import com.trendyol.stove.testing.e2e.rdbms.RelationalDatabaseExposedConfiguration
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.ValidationDsl
import com.trendyol.stove.testing.e2e.system.WithDsl
import com.trendyol.stove.testing.e2e.system.abstractions.ConfiguresExposedConfiguration
import com.trendyol.stove.testing.e2e.system.abstractions.SystemNotRegisteredException
import com.trendyol.stove.testing.e2e.system.abstractions.SystemOptions
import org.testcontainers.containers.PostgreSQLContainer

const val DEFAULT_POSTGRES_IMAGE_NAME = "postgres"

data class PostgresqlOptions(
    val databaseName: String = "stove-e2e-testing",
    val registry: String = DEFAULT_REGISTRY,
    val imageName: String = DEFAULT_POSTGRES_IMAGE_NAME,
    override val configureExposedConfiguration: (RelationalDatabaseExposedConfiguration) -> List<String> = { _ -> listOf() }
) : SystemOptions, ConfiguresExposedConfiguration<RelationalDatabaseExposedConfiguration>

internal class PostgresqlContext(
    container: PostgreSQLContainer<*>,
    configureExposedConfiguration: (RelationalDatabaseExposedConfiguration) -> List<String>
) : RelationalDatabaseContext<PostgreSQLContainer<*>>(container, configureExposedConfiguration)

internal fun TestSystem.withPostgresql(options: PostgresqlOptions = PostgresqlOptions()): TestSystem =
    withProvidedRegistry(options.imageName, options.registry, "postgres") {
        PostgreSQLContainer(it)
            .withDatabaseName(options.databaseName)
            .withUsername("sa")
            .withPassword("sa")
            .withReuse(this.options.keepDependenciesRunning)
    }.let { getOrRegister(PostgresqlSystem(this, PostgresqlContext(it, options.configureExposedConfiguration))) }
        .let { this }

internal fun TestSystem.postgresql(): PostgresqlSystem =
    getOrNone<PostgresqlSystem>().getOrElse {
        throw SystemNotRegisteredException(PostgresqlSystem::class)
    }

fun WithDsl.postgresql(configure: () -> PostgresqlOptions = { PostgresqlOptions() }): TestSystem =
    this.testSystem.withPostgresql(configure())

suspend fun ValidationDsl.postgresql(validation: suspend PostgresqlSystem.() -> Unit): Unit = validation(this.testSystem.postgresql())
