package com.trendol.stove.testing.e2e.rdbms.postgres

import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.containers.DEFAULT_REGISTRY
import com.trendyol.stove.testing.e2e.containers.withProvidedRegistry
import com.trendyol.stove.testing.e2e.rdbms.RelationalDatabaseContext
import com.trendyol.stove.testing.e2e.rdbms.RelationalDatabaseExposedConfiguration
import com.trendyol.stove.testing.e2e.rdbms.RelationalDatabaseSystem
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ConfiguresExposedConfiguration
import com.trendyol.stove.testing.e2e.system.abstractions.SystemNotRegisteredException
import com.trendyol.stove.testing.e2e.system.abstractions.SystemOptions
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import io.r2dbc.spi.ConnectionFactory
import org.testcontainers.containers.PostgreSQLContainer

const val DEFAULT_POSTGRES_IMAGE_NAME = "postgres"

data class PostgresqlOptions(
    val databaseName: String = "stove-e2e-testing",
    val registry: String = DEFAULT_REGISTRY,
    val imageName: String = DEFAULT_POSTGRES_IMAGE_NAME,
    override val configureExposedConfiguration: (RelationalDatabaseExposedConfiguration) -> List<String> = { _ -> listOf() },
) : SystemOptions, ConfiguresExposedConfiguration<RelationalDatabaseExposedConfiguration>

internal class PostgresqlContext(
    container: PostgreSQLContainer<*>,
    configureExposedConfiguration: (RelationalDatabaseExposedConfiguration) -> List<String>,
) : RelationalDatabaseContext<PostgreSQLContainer<*>>(container, configureExposedConfiguration)

fun TestSystem.withPostgresql(
    options: PostgresqlOptions = PostgresqlOptions(),
): TestSystem = withProvidedRegistry(options.imageName, options.registry, "postgres") {
    PostgreSQLContainer(it)
        .withDatabaseName(options.databaseName)
        .withUsername("sa")
        .withPassword("sa")
        .withReuse(this.options.keepDependenciesRunning)
}.let { getOrRegister(PostgresqlSystem(this, PostgresqlContext(it, options.configureExposedConfiguration))) }
    .let { this }

fun TestSystem.postgresql(): PostgresqlSystem = getOrNone<PostgresqlSystem>().getOrElse {
    throw SystemNotRegisteredException(PostgresqlSystem::class)
}

class PostgresqlSystem internal constructor(
    testSystem: TestSystem,
    context: PostgresqlContext,
) : RelationalDatabaseSystem<PostgresqlSystem>(testSystem, context) {
    override fun connectionFactory(exposedConfiguration: RelationalDatabaseExposedConfiguration): ConnectionFactory =
        PostgresqlConnectionConfiguration.builder().apply {
            host(exposedConfiguration.host)
            database(exposedConfiguration.database)
            port(exposedConfiguration.port)
            password(exposedConfiguration.password)
            username(exposedConfiguration.username)
        }.let { PostgresqlConnectionFactory(it.build()) }
}
