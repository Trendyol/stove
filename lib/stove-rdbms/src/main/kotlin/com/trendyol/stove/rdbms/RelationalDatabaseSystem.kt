@file:Suppress("unused")

package com.trendyol.stove.rdbms

import arrow.core.Some
import com.trendyol.stove.containers.StoveContainer
import com.trendyol.stove.functional.*
import com.trendyol.stove.reporting.Reports
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl
import com.trendyol.stove.system.closeAll
import kotlinx.coroutines.runBlocking
import kotliquery.*
import org.slf4j.*
import org.testcontainers.containers.JdbcDatabaseContainer

@Suppress("UNCHECKED_CAST", "MemberVisibilityCanBePrivate")
@StoveDsl
abstract class RelationalDatabaseSystem<SELF : RelationalDatabaseSystem<SELF>> protected constructor(
  final override val stove: Stove,
  protected val context: RelationalDatabaseContext
) : PluggedSystem,
  RunAware,
  ExposesConfiguration,
  Reports {
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  protected lateinit var exposedConfiguration: RelationalDatabaseExposedConfiguration

  protected lateinit var sqlOperations: NativeSqlOperations
  private val state: StateStorage<RelationalDatabaseExposedConfiguration> =
    stove.createStateStorage<RelationalDatabaseExposedConfiguration, RelationalDatabaseSystem<SELF>>()

  protected abstract fun database(exposedConfiguration: RelationalDatabaseExposedConfiguration): Session

  override suspend fun run() {
    exposedConfiguration = obtainExposedConfiguration()
    sqlOperations = NativeSqlOperations(database(exposedConfiguration))
  }

  private suspend fun obtainExposedConfiguration(): RelationalDatabaseExposedConfiguration = when (val runtime = context.runtime) {
    is StoveContainer -> startContainer(runtime as JdbcDatabaseContainer<*>)
    is ProvidedRuntime -> getProvidedConfig()
    else -> throw UnsupportedOperationException("Unsupported runtime type: ${runtime::class}")
  }

  private suspend fun startContainer(container: JdbcDatabaseContainer<*>): RelationalDatabaseExposedConfiguration = state.capture {
    container.start()
    RelationalDatabaseExposedConfiguration(
      jdbcUrl = container.jdbcUrl,
      host = container.host,
      port = container.firstMappedPort,
      password = container.password,
      username = container.username
    )
  }

  /**
   * Gets the provided configuration from subclass options.
   * Subclasses should override this to provide their specific provided config.
   */
  protected abstract fun getProvidedConfig(): RelationalDatabaseExposedConfiguration

  override fun configuration(): List<String> = context.configureExposedConfiguration(exposedConfiguration)

  suspend inline fun <reified T : Any> shouldQuery(
    query: String,
    crossinline mapper: (Row) -> T,
    crossinline assertion: (List<T>) -> Unit
  ): SELF {
    report(
      action = "Query",
      input = Some(query.trim()),
      metadata = mapOf("sql" to query.trim())
    ) {
      val results = internalSqlOperations.select(query) { mapper(it) }
      assertion(results)
      "${results.size} row(s) returned"
    }
    return this as SELF
  }

  suspend fun shouldExecute(sql: String): SELF {
    reportSqlExecution(sql) { internalSqlOperations.execute(sql) }
    return this as SELF
  }

  override suspend fun stop() {
    if (context.runtime is StoveContainer) {
      (context.runtime as JdbcDatabaseContainer<*>).stop()
    }
  }

  override fun close(): Unit =
    runBlocking {
      Try {
        // Cleanup callbacks are handled by subclasses.
        closeAll(
          { if (::sqlOperations.isInitialized) sqlOperations.close() },
          { executeWithReuseCheck { stop() } }
        )
      }.recover {
        val containerInfo = when (val runtime = context.runtime) {
          is JdbcDatabaseContainer<*> -> runtime.containerName
          is ProvidedRuntime -> "provided instance"
          else -> "unknown runtime"
        }
        logger.warn("got an error while stopping the container $containerInfo ")
      }.let { }
    }

  @PublishedApi
  internal var internalSqlOperations: NativeSqlOperations
    get() = sqlOperations
    set(value) {
      sqlOperations = value
    }

  companion object {
    /**
     * Exposes the [NativeSqlOperations] to the [RelationalDatabaseSystem].
     * Use this for advanced SQL operations not covered by the DSL.
     */
    @Suppress("unused")
    fun RelationalDatabaseSystem<*>.operations(): NativeSqlOperations = this.sqlOperations
  }
}
