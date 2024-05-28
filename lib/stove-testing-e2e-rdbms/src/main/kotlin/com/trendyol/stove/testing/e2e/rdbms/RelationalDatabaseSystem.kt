package com.trendyol.stove.testing.e2e.rdbms

import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.runBlocking
import org.slf4j.*

@Suppress("UNCHECKED_CAST", "MemberVisibilityCanBePrivate")
@StoveDsl
abstract class RelationalDatabaseSystem<SELF : RelationalDatabaseSystem<SELF>> protected constructor(
  final override val testSystem: TestSystem,
  protected val context: RelationalDatabaseContext<*>
) : PluggedSystem, RunAware, ExposesConfiguration {
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  protected lateinit var exposedConfiguration: RelationalDatabaseExposedConfiguration

  protected lateinit var sqlOperations: SqlOperations
  private val state: StateStorage<RelationalDatabaseExposedConfiguration> =
    testSystem.options.createStateStorage<RelationalDatabaseExposedConfiguration, RelationalDatabaseSystem<SELF>>()

  protected abstract fun connectionFactory(exposedConfiguration: RelationalDatabaseExposedConfiguration): ConnectionFactory

  override suspend fun run() {
    exposedConfiguration =
      state.capture {
        context.container.start()
        RelationalDatabaseExposedConfiguration(
          jdbcUrl = context.container.jdbcUrl,
          host = context.container.host,
          database = context.container.databaseName,
          port = context.container.firstMappedPort,
          password = context.container.password,
          username = context.container.username
        )
      }
    sqlOperations = SqlOperations(connectionFactory(exposedConfiguration))
    sqlOperations.open()
  }

  override fun configuration(): List<String> {
    return context.configureExposedConfiguration(exposedConfiguration) +
      listOf(
        "database.jdbcUrl=${exposedConfiguration.jdbcUrl}",
        "database.host=${exposedConfiguration.host}",
        "database.database=${exposedConfiguration.database}",
        "database.port=${exposedConfiguration.port}",
        "database.password=${exposedConfiguration.password}",
        "database.username=${exposedConfiguration.username}"
      )
  }

  @StoveDsl
  suspend inline fun <reified T : Any> shouldQuery(
    query: String,
    assertion: (List<T>) -> Unit
  ): SELF {
    var results: List<T> = emptyList()
    internalSqlOperations.transaction {
      results = select(query).map { r, rm -> mapper(r, rm, T::class) }.asFlow().toList()
    }

    assertion(results)
    return this as SELF
  }

  @StoveDsl
  suspend fun shouldExecute(sql: String): SELF {
    sqlOperations.transaction {
      execute(sql)
    }
    return this as SELF
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

  @PublishedApi
  internal var internalSqlOperations: SqlOperations
    get() = sqlOperations
    set(value) {
      sqlOperations = value
    }

  companion object {
    /**
     * Exposes the [SqlOperations] of the [RelationalDatabaseSystem].
     */
    @Suppress("unused")
    fun RelationalDatabaseSystem<*>.operations(): SqlOperations = this.sqlOperations
  }
}
