@file:Suppress("unused")

package com.trendyol.stove.mysql

import com.trendyol.stove.functional.*
import com.trendyol.stove.rdbms.*
import com.trendyol.stove.reporting.Reports
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl
import kotlinx.coroutines.runBlocking
import kotliquery.*
import org.slf4j.*

/**
 * MySQL database system for testing relational data operations.
 */
@StoveDsl
class MySqlSystem internal constructor(
  override val stove: Stove,
  private val mysqlContext: MySqlContext
) : PluggedSystem,
  RunAware,
  ExposesConfiguration,
  Reports {
  @PublishedApi
  internal lateinit var sqlOperations: NativeSqlOperations

  override val reportSystemName: String = "MySQL"

  private lateinit var exposedConfiguration: RelationalDatabaseExposedConfiguration
  private val logger: Logger = LoggerFactory.getLogger(javaClass)
  private val state: StateStorage<RelationalDatabaseExposedConfiguration> =
    stove.options.createStateStorage<RelationalDatabaseExposedConfiguration, MySqlSystem>()

  override suspend fun run() {
    exposedConfiguration = obtainExposedConfiguration()
    sqlOperations = NativeSqlOperations(database(exposedConfiguration))
    runMigrationsIfNeeded()
  }

  override suspend fun stop(): Unit = whenContainer { it.stop() }

  override fun close(): Unit = runBlocking {
    Try {
      if (::sqlOperations.isInitialized) {
        mysqlContext.options.cleanup(sqlOperations)
        sqlOperations.close()
      }
      executeWithReuseCheck { stop() }
    }.recover { logger.warn("MySQL stop failed", it) }
  }

  override fun configuration(): List<String> =
    mysqlContext.options.configureExposedConfiguration(exposedConfiguration)

  @StoveDsl
  suspend inline fun <reified T : Any> shouldQuery(
    query: String,
    crossinline mapper: (Row) -> T,
    crossinline assertion: (List<T>) -> Unit
  ): MySqlSystem {
    report(
      action = "Query",
      input = arrow.core.Some(query.trim()),
      metadata = mapOf("sql" to query.trim())
    ) {
      val results = sqlOperations.select(query) { mapper(it) }
      assertion(results)
      results
    }
    return this
  }

  @StoveDsl
  suspend fun shouldExecute(sql: String): MySqlSystem {
    report(
      action = "Execute SQL",
      input = arrow.core.Some(sql.trim()),
      metadata = mapOf("sql" to sql.trim())
    ) {
      val affectedRows = sqlOperations.execute(sql)
      check(affectedRows >= 0) { "Failed to execute sql: $sql" }
      "$affectedRows row(s) affected"
    }
    return this
  }

  /**
   * Pauses the container. Use with care, as it will pause the container which might affect other tests.
   * This operation is not supported when using a provided instance.
   * @return MySqlSystem
   */
  @StoveDsl
  suspend fun pause(): MySqlSystem {
    report(
      action = "Pause container",
      metadata = mapOf("operation" to "fault-injection")
    ) {
      withContainerOrWarn("pause") { it.pause() }
    }
    return this
  }

  /**
   * Unpauses the container. Use with care, as it will unpause the container which might affect other tests.
   * This operation is not supported when using a provided instance.
   * @return MySqlSystem
   */
  @StoveDsl
  suspend fun unpause(): MySqlSystem {
    report(action = "Unpause container") {
      withContainerOrWarn("unpause") { it.unpause() }
    }
    return this
  }

  private suspend fun obtainExposedConfiguration(): RelationalDatabaseExposedConfiguration =
    when {
      mysqlContext.options is ProvidedMySqlOptions -> mysqlContext.options.config
      mysqlContext.runtime is StoveMySqlContainer -> startMySqlContainer(mysqlContext.runtime)
      else -> throw UnsupportedOperationException("Unsupported runtime type: ${mysqlContext.runtime::class}")
    }

  private suspend fun startMySqlContainer(container: StoveMySqlContainer): RelationalDatabaseExposedConfiguration =
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
      mysqlContext.options.migrationCollection.run(
        MySqlMigrationContext(mysqlContext.options, sqlOperations) { executeAsRoot(it) }
      )
    }
  }

  private fun shouldRunMigrations(): Boolean = when {
    mysqlContext.options is ProvidedMySqlOptions -> mysqlContext.options.runMigrations
    mysqlContext.runtime is StoveMySqlContainer -> !state.isSubsequentRun() || stove.options.runMigrationsAlways
    else -> throw UnsupportedOperationException("Unsupported runtime type: ${mysqlContext.runtime::class}")
  }

  private fun createExecuteAsRootFn(): suspend (String) -> Unit = when {
    mysqlContext.options is ProvidedMySqlOptions -> { sql: String -> sqlOperations.execute(sql) }

    mysqlContext.runtime is StoveMySqlContainer -> { sql: String ->
      val container = mysqlContext.runtime
      // Use execCommand which works via Docker client directly, supporting both
      // fresh starts and subsequent runs with reuse (where container isn't "started" by testcontainers)
      container
        .execCommand(
          "/bin/bash",
          "-c",
          "mysql -u ${container.username} -p${container.password} ${container.databaseName} -e \"$sql\""
        ).let {
          check(it.exitCode == 0) { "Failed to execute sql: $sql, reason: ${it.stderr}" }
        }
    }

    else -> throw UnsupportedOperationException("Unsupported runtime type: ${mysqlContext.runtime::class}")
  }

  private fun database(exposedConfiguration: RelationalDatabaseExposedConfiguration): Session = sessionOf(
    url = exposedConfiguration.jdbcUrl,
    user = exposedConfiguration.username,
    password = exposedConfiguration.password
  )

  private inline fun withContainerOrWarn(
    operation: String,
    action: (StoveMySqlContainer) -> Unit
  ): MySqlSystem = when (val runtime = mysqlContext.runtime) {
    is StoveMySqlContainer -> {
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

  private inline fun whenContainer(action: (StoveMySqlContainer) -> Unit) {
    if (mysqlContext.runtime is StoveMySqlContainer) {
      action(mysqlContext.runtime)
    }
  }

  companion object {
    /**
     * Exposes the [NativeSqlOperations] to the [MySqlSystem].
     * Use this for advanced SQL operations not covered by the DSL.
     */
    @StoveDsl
    fun MySqlSystem.operations(): NativeSqlOperations = sqlOperations
  }
}
