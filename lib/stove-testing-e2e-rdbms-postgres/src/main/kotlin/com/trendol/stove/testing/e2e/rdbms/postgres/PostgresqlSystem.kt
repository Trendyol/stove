package com.trendol.stove.testing.e2e.rdbms.postgres

import com.trendyol.stove.testing.e2e.rdbms.RelationalDatabaseExposedConfiguration
import com.trendyol.stove.testing.e2e.rdbms.RelationalDatabaseSystem
import com.trendyol.stove.testing.e2e.system.TestSystem
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import io.r2dbc.spi.ConnectionFactory

class PostgresqlSystem internal constructor(
    testSystem: TestSystem,
    context: PostgresqlContext
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
