@file:Suppress("unused")

package com.trendyol.stove.testing.e2e.rdbms.mssql

import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.rdbms.*
import com.trendyol.stove.testing.e2e.reporting.Reports
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import kotlinx.coroutines.runBlocking
import kotliquery.*
import org.slf4j.*

@StoveDsl
class MsSqlSystem internal constructor(
  override val testSystem: TestSystem,
  private val mssqlContext: MsSqlContext
) : PluggedSystem,
  RunAware,
  ExposesConfiguration,
  Reports {
  @PublishedApi
  internal lateinit var sqlOperations: NativeSqlOperations

  override val reportSystemName: String = "MSSQL"

  private lateinit var exposedConfiguration: RelationalDatabaseExposedConfiguration
  private val logger: Logger = LoggerFactory.getLogger(javaClass)
  private val state: StateStorage<RelationalDatabaseExposedConfiguration> =
    testSystem.options.createStateStorage<RelationalDatabaseExposedConfiguration, MsSqlSystem>()

  override suspend fun run() {
    exposedConfiguration = obtainExposedConfiguration()
    sqlOperations = NativeSqlOperations(database(exposedConfiguration))
    runMigrationsIfNeeded()
  }

  override suspend fun stop(): Unit = whenContainer { it.stop() }

  override fun close(): Unit = runBlocking {
    Try {
      if (::sqlOperations.isInitialized) {
        mssqlContext.options.cleanup(sqlOperations)
        sqlOperations.close()
      }
      executeWithReuseCheck { stop() }
    }.recover {
      logger.warn("got an error while stopping the MSSQL system")
    }.let { }
  }

  override fun configuration(): List<String> =
    mssqlContext.options.configureExposedConfiguration(exposedConfiguration)

  @StoveDsl
  suspend inline fun <reified T : Any> shouldQuery(
    query: String,
    crossinline mapper: (Row) -> T,
    crossinline assertion: (List<T>) -> Unit
  ): MsSqlSystem {
    recordAndExecute(
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
  fun shouldExecute(sql: String): MsSqlSystem {
    executeAndRecord(
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

  @StoveDsl
  suspend fun ops(operations: suspend NativeSqlOperations.() -> Unit) {
    operations(sqlOperations)
  }

  /**
   * Pauses the container. Use with care, as it will pause the container which might affect other tests.
   * This operation is not supported when using a provided instance.
   * @return MsSqlSystem
   */
  @StoveDsl
  fun pause(): MsSqlSystem {
    executeAndRecord(
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
   * @return MsSqlSystem
   */
  @StoveDsl
  fun unpause(): MsSqlSystem {
    executeAndRecord(action = "Unpause container") {
      withContainerOrWarn("unpause") { it.unpause() }
    }
    return this
  }

  private suspend fun obtainExposedConfiguration(): RelationalDatabaseExposedConfiguration =
    when {
      mssqlContext.options is ProvidedMsSqlOptions -> mssqlContext.options.config
      mssqlContext.runtime is StoveMsSqlContainer -> startMsSqlContainer(mssqlContext.runtime)
      else -> throw UnsupportedOperationException("Unsupported runtime type: ${mssqlContext.runtime::class}")
    }

  private suspend fun startMsSqlContainer(container: StoveMsSqlContainer): RelationalDatabaseExposedConfiguration =
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
    if (!shouldRunMigrations()) return

    val executeAsRoot = createExecuteAsRootFn()
    createDatabaseIfNeeded(executeAsRoot)

    mssqlContext.options.migrationCollection.run(
      SqlMigrationContext(mssqlContext.options, sqlOperations) { executeAsRoot(it) }
    )
  }

  private fun shouldRunMigrations(): Boolean = when {
    mssqlContext.options is ProvidedMsSqlOptions -> mssqlContext.options.runMigrations
    mssqlContext.runtime is StoveMsSqlContainer -> !state.isSubsequentRun() || testSystem.options.runMigrationsAlways
    else -> throw UnsupportedOperationException("Unsupported runtime type: ${mssqlContext.runtime::class}")
  }

  private fun createExecuteAsRootFn(): suspend (String) -> Unit = when {
    mssqlContext.options is ProvidedMsSqlOptions -> { sql: String -> sqlOperations.execute(sql) }
    mssqlContext.runtime is StoveMsSqlContainer -> containerExecuteAsRoot(mssqlContext.runtime)
    else -> throw UnsupportedOperationException("Unsupported runtime type: ${mssqlContext.runtime::class}")
  }

  private suspend fun createDatabaseIfNeeded(executeAsRoot: suspend (String) -> Unit) {
    if (mssqlContext.runtime is StoveMsSqlContainer) {
      executeAsRoot("CREATE DATABASE ${mssqlContext.options.databaseName}")
    }
  }

  private fun containerExecuteAsRoot(container: StoveMsSqlContainer): suspend (String) -> Unit = { sql: String ->
    // Use execCommand which works via Docker client directly, supporting both
    // fresh starts and subsequent runs with reuse (where container isn't "started" by testcontainers)
    container
      .execCommand(
        "/opt/${mssqlContext.options.container.toolsPath.path}/bin/sqlcmd",
        "-S",
        "localhost",
        "-U",
        mssqlContext.options.userName,
        "-C",
        "-P",
        mssqlContext.options.password,
        "-Q",
        sql
      ).let {
        check(it.exitCode == 0) {
          """
          Failed to execute sql: $sql
          Reason: ${it.stderr}
          ToolsPath: ${mssqlContext.options.container.toolsPath}
          ContainerTag: ${mssqlContext.options.container.imageWithTag}
          Exit code: ${it.exitCode}
          Recommendation: Try setting the toolsPath to ToolsPath.Before2019 or ToolsPath.After2019 depending on your mssql version.
          """.trimIndent()
        }
      }
  }

  private fun database(exposedConfiguration: RelationalDatabaseExposedConfiguration): Session = sessionOf(
    exposedConfiguration.jdbcUrl,
    exposedConfiguration.username,
    exposedConfiguration.password
  )

  private inline fun withContainerOrWarn(
    operation: String,
    action: (StoveMsSqlContainer) -> Unit
  ): MsSqlSystem = when (val runtime = mssqlContext.runtime) {
    is StoveMsSqlContainer -> {
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

  private inline fun whenContainer(action: (StoveMsSqlContainer) -> Unit) {
    if (mssqlContext.runtime is StoveMsSqlContainer) {
      action(mssqlContext.runtime)
    }
  }

  companion object {
    /**
     * Exposes the [NativeSqlOperations] to the [MsSqlSystem].
     * Use this for advanced SQL operations not covered by the DSL.
     */
    @StoveDsl
    fun MsSqlSystem.operations(): NativeSqlOperations {
      recordSuccess(action = "Access underlying NativeSqlOperations")
      return sqlOperations
    }
  }
}
