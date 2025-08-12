package com.trendyol.stove.testing.e2e.rdbms.postgres

import com.trendyol.stove.testing.e2e.rdbms.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import kotliquery.*

@StoveDsl
class PostgresqlSystem internal constructor(
  testSystem: TestSystem,
  private val postgresContext: PostgresqlContext
) : RelationalDatabaseSystem<PostgresqlSystem>(testSystem, postgresContext) {
  override suspend fun run() {
    super.run()
    val executeAsRoot = { sql: String ->
      postgresContext.container
        .execInContainer(
          "/bin/bash",
          "-c",
          "psql -U ${postgresContext.container.username} -d ${postgresContext.container.databaseName} -c \"$sql\""
        ).let {
          check(it.exitCode == 0) { "Failed to execute sql: $sql, reason: ${it.stderr}" }
        }
    }
    postgresContext.options.migrationCollection.run(
      PostgresSqlMigrationContext(
        postgresContext.options,
        sqlOperations
      ) { executeAsRoot(it) }
    )
  }

  /**
   * Pauses the container. Use with care, as it will pause the container which might affect other tests.
   * @return KafkaSystem
   */
  @StoveDsl
  fun pause(): PostgresqlSystem = postgresContext.container.pause().let { this }

  /**
   * Unpauses the container. Use with care, as it will unpause the container which might affect other tests.
   * @return KafkaSystem
   */
  @StoveDsl
  fun unpause(): PostgresqlSystem = postgresContext.container.unpause().let { this }

  override fun database(
    exposedConfiguration: RelationalDatabaseExposedConfiguration
  ): Session = sessionOf(
    url = exposedConfiguration.jdbcUrl,
    user = exposedConfiguration.username,
    password = exposedConfiguration.password
  )
}
