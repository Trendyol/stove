package com.trendyol.stove.testing.e2e.rdbms.mssql

import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.rdbms.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.*
import io.r2dbc.mssql.*
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.runBlocking
import org.slf4j.*

@MssqlDsl
class MsSqlSystem internal constructor(
  override val testSystem: TestSystem,
  private val context: MsSqlContext
) : PluggedSystem, RunAware, ExposesConfiguration {
  private lateinit var sqlOperations: SqlOperations
  private lateinit var exposedConfiguration: RelationalDatabaseExposedConfiguration
  private val logger: Logger = LoggerFactory.getLogger(javaClass)
  private val state: StateStorage<RelationalDatabaseExposedConfiguration> =
    testSystem.options.createStateStorage<RelationalDatabaseExposedConfiguration, MsSqlSystem>()

  override suspend fun run() {
    exposedConfiguration =
      state.capture {
        context.container.start()
        context.container
          .withUrlParam("application-name", context.options.applicationName)
          .withUrlParam("sendStringParametersAsUnicode", "false")
          .withUrlParam("database", context.options.databaseName)
        RelationalDatabaseExposedConfiguration(
          host = context.container.host,
          jdbcUrl = context.container.getJdbcUrl(),
          port = context.container.firstMappedPort,
          database = context.options.databaseName,
          password = context.options.password,
          username = context.options.userName
        )
      }
    val executeAsRoot = { sql: String ->
      context.container.execInContainer(
        "/opt/mssql-tools/bin/sqlcmd",
        "-S",
        "localhost",
        "-U",
        context.options.userName,
        "-P",
        context.options.password,
        "-Q",
        sql
      )
    }
    executeAsRoot("CREATE DATABASE ${context.options.databaseName}")
    sqlOperations = SqlOperations(connectionFactory(exposedConfiguration))
    sqlOperations.open()
    context.options.migrationCollection.run(SqlMigrationContext(context.options, sqlOperations) { executeAsRoot(it) })
  }

  override suspend fun stop(): Unit = context.container.stop()

  override fun close(): Unit =
    runBlocking {
      Try {
        sqlOperations.close()
        executeWithReuseCheck { stop() }
      }.recover {
        logger.warn("got an error while stopping the container ${context.container.containerName} ")
      }.let { }
    }

  override fun configuration(): List<String> = context.configureExposedConfiguration(exposedConfiguration)

  @MssqlDsl
  suspend fun ops(operations: suspend Handle.() -> Unit) {
    sqlOperations.isOpen().let {
      if (it) {
        sqlOperations.transaction(operations)
      } else {
        error("The connection is not open. Please check the connection status.")
      }
    }
  }

  /**
   * Pauses the container. Use with care, as it will pause the container which might affect other tests.
   * @return KafkaSystem
   */
  @MssqlDsl
  fun pause(): MsSqlSystem = context.container.pause().let { this }

  /**
   * Unpauses the container. Use with care, as it will unpause the container which might affect other tests.
   * @return KafkaSystem
   */
  @MssqlDsl
  fun unpause(): MsSqlSystem = context.container.unpause().let { this }

  private fun connectionFactory(exposedConfiguration: RelationalDatabaseExposedConfiguration): ConnectionFactory =
    MssqlConnectionConfiguration.builder().apply {
      host(exposedConfiguration.host)
      database(context.options.databaseName)
      port(exposedConfiguration.port)
      password(exposedConfiguration.password)
      username(exposedConfiguration.username)
      applicationName(context.options.applicationName)
    }.let { MssqlConnectionFactory(it.build()) }
}
