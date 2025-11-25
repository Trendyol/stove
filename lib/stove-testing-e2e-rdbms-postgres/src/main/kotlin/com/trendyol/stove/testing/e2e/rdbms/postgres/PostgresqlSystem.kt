@file:Suppress("unused")

package com.trendyol.stove.testing.e2e.rdbms.postgres

import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.rdbms.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import kotlinx.coroutines.runBlocking
import kotliquery.*
import org.slf4j.*

@StoveDsl
class PostgresqlSystem internal constructor(
  override val testSystem: TestSystem,
  private val postgresContext: PostgresqlContext
) : PluggedSystem,
  RunAware,
  ExposesConfiguration {
  @PublishedApi
  internal lateinit var sqlOperations: NativeSqlOperations

  private lateinit var exposedConfiguration: RelationalDatabaseExposedConfiguration
  private val logger: Logger = LoggerFactory.getLogger(javaClass)
  private val state: StateStorage<RelationalDatabaseExposedConfiguration> =
    testSystem.options.createStateStorage<RelationalDatabaseExposedConfiguration, PostgresqlSystem>()

  override suspend fun run() {
    exposedConfiguration = obtainExposedConfiguration()
    sqlOperations = NativeSqlOperations(database(exposedConfiguration))
    runMigrationsIfNeeded()
  }

  override suspend fun stop(): Unit = whenContainer { it.stop() }

  override fun close(): Unit = runBlocking {
    Try {
      if (::sqlOperations.isInitialized) {
        postgresContext.options.cleanup(sqlOperations)
        sqlOperations.close()
      }
      executeWithReuseCheck { stop() }
    }.recover { logger.warn("PostgreSQL stop failed", it) }
  }

  override fun configuration(): List<String> =
    postgresContext.options.configureExposedConfiguration(exposedConfiguration)

  @StoveDsl
  inline fun <reified T : Any> shouldQuery(
    query: String,
    crossinline mapper: (Row) -> T,
    assertion: (List<T>) -> Unit
  ): PostgresqlSystem {
    val results = sqlOperations.select(query) { mapper(it) }
    assertion(results)
    return this
  }

  @StoveDsl
  fun shouldExecute(sql: String): PostgresqlSystem {
    check(sqlOperations.execute(sql) >= 0) { "Failed to execute sql: $sql" }
    return this
  }

  /**
   * Pauses the container. Use with care, as it will pause the container which might affect other tests.
   * This operation is not supported when using a provided instance.
   * @return PostgresqlSystem
   */
  @StoveDsl
  fun pause(): PostgresqlSystem = withContainerOrWarn("pause") { it.pause() }

  /**
   * Unpauses the container. Use with care, as it will unpause the container which might affect other tests.
   * This operation is not supported when using a provided instance.
   * @return PostgresqlSystem
   */
  @StoveDsl
  fun unpause(): PostgresqlSystem = withContainerOrWarn("unpause") { it.unpause() }

  private suspend fun obtainExposedConfiguration(): RelationalDatabaseExposedConfiguration =
    when {
      postgresContext.options is ProvidedPostgresqlOptions -> postgresContext.options.config
      postgresContext.runtime is StovePostgresqlContainer -> startPostgresContainer(postgresContext.runtime)
      else -> throw UnsupportedOperationException("Unsupported runtime type: ${postgresContext.runtime::class}")
    }

  private suspend fun startPostgresContainer(container: StovePostgresqlContainer): RelationalDatabaseExposedConfiguration =
    state.capture {
      container.start()
      RelationalDatabaseExposedConfiguration(
        jdbcUrl = container.jdbcUrl,
        host = container.host,
        port = container.firstMappedPort,
        username = container.username,
        password = container.password
      )
    }

  private suspend fun runMigrationsIfNeeded() {
    if (shouldRunMigrations()) {
      val executeAsRoot = createExecuteAsRootFn()
      postgresContext.options.migrationCollection.run(
        PostgresSqlMigrationContext(postgresContext.options, sqlOperations) { executeAsRoot(it) }
      )
    }
  }

  private fun shouldRunMigrations(): Boolean = when {
    postgresContext.options is ProvidedPostgresqlOptions -> postgresContext.options.runMigrations
    postgresContext.runtime is StovePostgresqlContainer -> !state.isSubsequentRun() || testSystem.options.runMigrationsAlways
    else -> throw UnsupportedOperationException("Unsupported runtime type: ${postgresContext.runtime::class}")
  }

  private fun createExecuteAsRootFn(): suspend (String) -> Unit = when {
    postgresContext.options is ProvidedPostgresqlOptions -> { sql: String -> sqlOperations.execute(sql) }

    postgresContext.runtime is StovePostgresqlContainer -> { sql: String ->
      val container = postgresContext.runtime
      // Use execCommand which works via Docker client directly, supporting both
      // fresh starts and subsequent runs with reuse (where container isn't "started" by testcontainers)
      container
        .execCommand(
          "/bin/bash",
          "-c",
          "psql -U ${container.username} -d ${container.databaseName} -c \"$sql\""
        ).let {
          check(it.exitCode == 0) { "Failed to execute sql: $sql, reason: ${it.stderr}" }
        }
    }

    else -> throw UnsupportedOperationException("Unsupported runtime type: ${postgresContext.runtime::class}")
  }

  private fun database(exposedConfiguration: RelationalDatabaseExposedConfiguration): Session = sessionOf(
    url = exposedConfiguration.jdbcUrl,
    user = exposedConfiguration.username,
    password = exposedConfiguration.password
  )

  private inline fun withContainerOrWarn(
    operation: String,
    action: (StovePostgresqlContainer) -> Unit
  ): PostgresqlSystem = when (val runtime = postgresContext.runtime) {
    is StovePostgresqlContainer -> {
      action(runtime)
      this
    }

    is ProvidedRuntime -> {
      logger.warn("$operation() is not supported when using a provided instance")
      this
    }

    else -> {
      throw UnsupportedOperationException("Unsupported runtime type: ${runtime::class}")
    }
  }

  private inline fun whenContainer(action: (StovePostgresqlContainer) -> Unit) {
    if (postgresContext.runtime is StovePostgresqlContainer) {
      action(postgresContext.runtime)
    }
  }

  companion object {
    @StoveDsl
    fun PostgresqlSystem.operations(): NativeSqlOperations = sqlOperations
  }
}
