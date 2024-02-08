package com.trendol.stove.testing.e2e.rdbms.postgres

import com.trendyol.stove.testing.e2e.rdbms.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import io.r2dbc.postgresql.*
import io.r2dbc.spi.ConnectionFactory

@StoveDsl
class PostgresqlSystem internal constructor(
    testSystem: TestSystem,
    private val postgresContext: PostgresqlContext
) : RelationalDatabaseSystem<PostgresqlSystem>(testSystem, postgresContext) {
    override suspend fun run() {
        super.run()
        val executeAsRoot = { sql: String ->
            postgresContext.container.execInContainer(
                "/bin/bash",
                "-c",
                "psql -U ${postgresContext.container.username} -d ${postgresContext.container.databaseName} -c \"$sql\""
            )
        }
        postgresContext.options.migrationCollection.run(
            PostgresSqlMigrationContext(postgresContext.options, sqlOperations) {
                executeAsRoot(
                    it
                )
            }
        )
    }

    override fun connectionFactory(exposedConfiguration: RelationalDatabaseExposedConfiguration): ConnectionFactory =
        PostgresqlConnectionConfiguration.builder().apply {
            host(exposedConfiguration.host)
            database(exposedConfiguration.database)
            port(exposedConfiguration.port)
            password(exposedConfiguration.password)
            username(exposedConfiguration.username)
        }.let { PostgresqlConnectionFactory(it.build()) }
}
