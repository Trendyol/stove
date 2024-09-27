package com.trendyol.stove.testing.e2e.rdbms

import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.slf4j.*
import java.sql.ResultSet

@Suppress("UNCHECKED_CAST", "MemberVisibilityCanBePrivate")
@StoveDsl
abstract class RelationalDatabaseSystem<SELF : RelationalDatabaseSystem<SELF>> protected constructor(
  final override val testSystem: TestSystem,
  protected val context: RelationalDatabaseContext<*>
) : PluggedSystem, RunAware, ExposesConfiguration {
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  protected lateinit var exposedConfiguration: RelationalDatabaseExposedConfiguration

  protected lateinit var sqlOperations: NativeSqlOperations
  private val state: StateStorage<RelationalDatabaseExposedConfiguration> =
    testSystem.options.createStateStorage<RelationalDatabaseExposedConfiguration, RelationalDatabaseSystem<SELF>>()

  protected abstract fun database(exposedConfiguration: RelationalDatabaseExposedConfiguration): Database

  override suspend fun run() {
    exposedConfiguration = state.capture {
      context.container.start()
      RelationalDatabaseExposedConfiguration(
        jdbcUrl = context.container.jdbcUrl,
        host = context.container.host,
        port = context.container.firstMappedPort,
        password = context.container.password,
        username = context.container.username
      )
    }
    sqlOperations = NativeSqlOperations(database(exposedConfiguration))
  }

  override fun configuration(): List<String> = context.configureExposedConfiguration(exposedConfiguration)

  @StoveDsl
  suspend inline fun <reified T : Any> shouldQuery(
    query: String,
    crossinline mapper: (ResultSet) -> T,
    assertion: (List<T>) -> Unit
  ): SELF {
    val results = internalSqlOperations.select(query) { mapper(it) }
    assertion(results)
    return this as SELF
  }

  @StoveDsl
  fun shouldExecute(sql: String): SELF {
    check(internalSqlOperations.execute(sql) >= 0) { "Failed to execute sql: $sql" }
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
  internal var internalSqlOperations: NativeSqlOperations
    get() = sqlOperations
    set(value) {
      sqlOperations = value
    }

  companion object {
    @Suppress("unused")
    fun RelationalDatabaseSystem<*>.operations(): NativeSqlOperations = this.sqlOperations
  }
}

typealias AssertionCollectionFunction<T> = (List<T>) -> Unit
