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

internal class PostgresqlContext(
    container: PostgreSQLContainer<*>,
    configureExposedConfiguration: (RelationalDatabaseExposedConfiguration) -> List<String>,
) : RelationalDatabaseContext<PostgreSQLContainer<*>>(container, configureExposedConfiguration)

fun TestSystem.withPostgresql(
    registry: String = DEFAULT_REGISTRY,
    imageName: String = DEFAULT_POSTGRES_IMAGE_NAME,
    compatibleSubstitute: String? = null,
    configureExposedConfiguration: (RelationalDatabaseExposedConfiguration) -> List<String> = { _ ->
        listOf()
    },
): TestSystem {
    val container = withProvidedRegistry(imageName, registry, compatibleSubstitute) {
        PostgreSQLContainer(it).withDatabaseName("integration-tests-db").withUsername("sa").withPassword("sa")
    }
    return getOrRegister(PostgresqlSystem(this, PostgresqlContext(container, configureExposedConfiguration))).let { this }
}

fun TestSystem.postgresql(): PostgresqlSystem = getOrNone<PostgresqlSystem>().getOrElse {
    throw SystemNotRegisteredException(PostgresqlSystem::class)
}

class PostgresqlSystem internal constructor(
    testSystem: TestSystem,
    context: PostgresqlContext,
) : RelationalDatabaseSystem<PostgresqlSystem>(testSystem, context) {
    override val connectionFactory: ConnectionFactory
        get() {
            val builder = PostgresqlConnectionConfiguration.builder().apply {
                host(context.container.host)
                database(context.container.databaseName)
                port(context.container.firstMappedPort)
                password(context.container.password)
                username(context.container.username)
            }
            return PostgresqlConnectionFactory(builder.build())
        }
}
