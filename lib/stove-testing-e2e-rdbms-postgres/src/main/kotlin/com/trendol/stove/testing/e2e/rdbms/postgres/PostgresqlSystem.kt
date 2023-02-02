package com.trendol.stove.testing.e2e.rdbms.postgres

import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.containers.DEFAULT_REGISTRY
import com.trendyol.stove.testing.e2e.containers.withProvidedRegistry
import com.trendyol.stove.testing.e2e.rdbms.RelationalDatabaseContext
import com.trendyol.stove.testing.e2e.rdbms.RelationalDatabaseExposedConfiguration
import com.trendyol.stove.testing.e2e.rdbms.RelationalDatabaseSystem
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.SystemNotRegisteredException
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import io.r2dbc.spi.ConnectionFactory
import org.testcontainers.containers.PostgreSQLContainer

const val DEFAULT_POSTGRES_IMAGE_NAME = "postgres"

data class PostgresqlOptions(
    val databaseName: String = "stove-e2e-testing",
    val registry: String = DEFAULT_REGISTRY,
    val imageName: String = DEFAULT_POSTGRES_IMAGE_NAME,
    val configureExposedConfiguration: (RelationalDatabaseExposedConfiguration) -> List<String> = { _ -> listOf() },
)

internal class PostgresqlContext(
    container: PostgreSQLContainer<*>,
    configureExposedConfiguration: (RelationalDatabaseExposedConfiguration) -> List<String>,
) : RelationalDatabaseContext<PostgreSQLContainer<*>>(container, configureExposedConfiguration)

fun TestSystem.withPostgresql(
    options: PostgresqlOptions = PostgresqlOptions(),
): TestSystem {
    val container = withProvidedRegistry(options.imageName, options.registry, "postgres") {
        PostgreSQLContainer(it).withDatabaseName(options.databaseName).withUsername("sa").withPassword("sa")
    }
    return getOrRegister(PostgresqlSystem(this, PostgresqlContext(container, options.configureExposedConfiguration))).let { this }
}

fun TestSystem.postgresql(): PostgresqlSystem = getOrNone<PostgresqlSystem>().getOrElse {
    throw SystemNotRegisteredException(PostgresqlSystem::class)
}

class PostgresqlSystem internal constructor(
    testSystem: TestSystem,
    context: PostgresqlContext,
) : RelationalDatabaseSystem<PostgresqlSystem>(testSystem, context) {
    override fun connectionFactory(): ConnectionFactory = PostgresqlConnectionConfiguration.builder().apply {
        host(context.container.host)
        database(context.container.databaseName)
        port(context.container.firstMappedPort)
        password(context.container.password)
        username(context.container.username)
    }.let { PostgresqlConnectionFactory(it.build()) }
}
