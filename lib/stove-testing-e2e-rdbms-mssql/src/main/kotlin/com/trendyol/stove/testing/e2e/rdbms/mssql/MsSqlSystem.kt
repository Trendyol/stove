package com.trendyol.stove.testing.e2e.rdbms.mssql

import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.rdbms.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.slf4j.*

@StoveDsl
class MsSqlSystem internal constructor(
  testSystem: TestSystem,
  private val mssqlContext: MsSqlContext
) : RelationalDatabaseSystem<MsSqlSystem>(testSystem, mssqlContext) {
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  override suspend fun run() {
    super.run()
    val executeAsRoot = executeAsRootFn()
    executeAsRoot("CREATE DATABASE ${mssqlContext.options.databaseName}")
    mssqlContext.options.migrationCollection.run(
      SqlMigrationContext(mssqlContext.options, sqlOperations) {
        executeAsRoot(it)
      }
    )
  }

  override fun configuration(): List<String> = context.configureExposedConfiguration(exposedConfiguration)

  @StoveDsl
  suspend fun ops(operations: suspend NativeSqlOperations.() -> Unit) {
    operations(sqlOperations)
  }

  /**
   * Pauses the container. Use with care, as it will pause the container which might affect other tests.
   * @return KafkaSystem
   */
  @StoveDsl
  fun pause(): MsSqlSystem = mssqlContext.container.pause().let { this }

  /**
   * Unpauses the container. Use with care, as it will unpause the container which might affect other tests.
   * @return KafkaSystem
   */
  @StoveDsl
  fun unpause(): MsSqlSystem = mssqlContext.container.unpause().let { this }

  override suspend fun stop(): Unit = context.container.stop()

  private fun executeAsRootFn() = { sql: String ->
    context.container
      .execInContainer(
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

  override fun database(exposedConfiguration: RelationalDatabaseExposedConfiguration): Database =
    Database.connect(
      url = exposedConfiguration.jdbcUrl,
      driver = "com.microsoft.sqlserver.jdbc.SQLServerDriver",
      user = exposedConfiguration.username,
      password = exposedConfiguration.password
    )

  override fun close(): Unit = runBlocking {
    Try {
      sqlOperations.close()
      executeWithReuseCheck { stop() }
    }.recover {
      logger.warn("got an error while stopping the container ${context.container.containerName} ")
    }.let { }
  }
}
