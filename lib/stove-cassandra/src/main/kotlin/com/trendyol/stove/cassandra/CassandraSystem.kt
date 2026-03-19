@file:Suppress("unused")

package com.trendyol.stove.cassandra

import com.datastax.oss.driver.api.core.*
import com.datastax.oss.driver.api.core.cql.*
import com.trendyol.stove.functional.*
import com.trendyol.stove.reporting.Reports
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.*
import kotlinx.coroutines.runBlocking
import org.slf4j.*
import java.net.InetSocketAddress

/**
 * Cassandra database system for testing CQL operations.
 *
 * Provides a DSL for testing Cassandra operations:
 * - CQL statement execution
 * - Query result assertions
 * - Keyspace and table management
 *
 * ## Executing Statements
 *
 * ```kotlin
 * cassandra {
 *     shouldExecute("INSERT INTO my_keyspace.users (id, name) VALUES (uuid(), 'John')")
 * }
 * ```
 *
 * ## Querying Data
 *
 * ```kotlin
 * cassandra {
 *     shouldQuery("SELECT * FROM my_keyspace.users") { resultSet ->
 *         val rows = resultSet.all()
 *         rows shouldHaveSize 1
 *         rows.first().getString("name") shouldBe "John"
 *     }
 * }
 * ```
 *
 * ## Using the Raw Session
 *
 * ```kotlin
 * cassandra {
 *     session().execute("TRUNCATE my_keyspace.users")
 * }
 * ```
 *
 * ## Test Workflow Example
 *
 * ```kotlin
 * test("should store user in Cassandra via API") {
 *     stove {
 *         // Create user via API
 *         http {
 *             postAndExpectBody<UserResponse>(
 *                 uri = "/users",
 *                 body = CreateUserRequest(name = "John").some()
 *             ) { response ->
 *                 response.status shouldBe 201
 *             }
 *         }
 *
 *         // Verify in Cassandra
 *         cassandra {
 *             shouldQuery("SELECT * FROM my_keyspace.users WHERE name = 'John'") { resultSet ->
 *                 val rows = resultSet.all()
 *                 rows shouldHaveSize 1
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * ## Configuration
 *
 * ```kotlin
 * Stove()
 *     .with {
 *         cassandra {
 *             CassandraSystemOptions(
 *                 keyspace = "my_keyspace",
 *                 configureExposedConfiguration = { cfg ->
 *                     listOf(
 *                         "spring.cassandra.contact-points=${cfg.host}:${cfg.port}",
 *                         "spring.cassandra.local-datacenter=${cfg.datacenter}",
 *                         "spring.cassandra.keyspace-name=${cfg.keyspace}"
 *                     )
 *                 }
 *             )
 *         }
 *     }
 * ```
 *
 * @property stove The parent test system.
 * @see CassandraSystemOptions
 * @see CassandraExposedConfiguration
 */
@CassandraDsl
class CassandraSystem internal constructor(
  override val stove: Stove,
  private val context: CassandraContext
) : PluggedSystem,
  RunAware,
  ExposesConfiguration,
  Reports {
  @PublishedApi
  internal lateinit var cqlSession: CqlSession

  override val reportSystemName: String = "Cassandra"
  private lateinit var exposedConfiguration: CassandraExposedConfiguration
  private val logger: Logger = LoggerFactory.getLogger(javaClass)
  private val state: StateStorage<CassandraExposedConfiguration> =
    stove.options.createStateStorage<CassandraExposedConfiguration, CassandraSystem>()

  override suspend fun run() {
    exposedConfiguration = obtainExposedConfiguration()
    cqlSession = createSession(exposedConfiguration)
    runMigrationsIfNeeded()
  }

  override suspend fun stop(): Unit = whenContainer { it.stop() }

  override fun configuration(): List<String> = context.options.configureExposedConfiguration(exposedConfiguration)

  override fun close(): Unit = runBlocking {
    Try {
      if (::cqlSession.isInitialized) {
        context.options.cleanup(cqlSession)
        cqlSession.close()
      }
      executeWithReuseCheck { stop() }
    }.recover { logger.warn("Cassandra session close failed", it) }
  }

  /**
   * Executes a CQL statement and asserts that it completes without errors.
   *
   * @param cql The CQL statement to execute
   * @return This [CassandraSystem] for chaining
   */
  suspend fun shouldExecute(cql: String): CassandraSystem {
    report(
      action = "Execute CQL",
      input = arrow.core.Some(mapOf("cql" to cql))
    ) {
      cqlSession.execute(cql)
    }
    return this
  }

  /**
   * Executes a CQL query and passes the [ResultSet] to the [assertion] block.
   *
   * @param cql The CQL query to execute
   * @param assertion A block that receives the [ResultSet] for assertions
   * @return This [CassandraSystem] for chaining
   */
  suspend fun shouldQuery(
    cql: String,
    assertion: (ResultSet) -> Unit
  ): CassandraSystem {
    report(
      action = "Query Cassandra",
      input = arrow.core.Some(mapOf("cql" to cql))
    ) {
      val resultSet = cqlSession.execute(cql)
      assertion(resultSet)
      resultSet
    }
    return this
  }

  /**
   * Executes a [BoundStatement] and asserts that it completes without errors.
   *
   * @param statement The prepared [BoundStatement] to execute
   * @return This [CassandraSystem] for chaining
   */
  suspend fun shouldExecute(statement: BoundStatement): CassandraSystem {
    report(action = "Execute Bound Statement") {
      cqlSession.execute(statement)
    }
    return this
  }

  /**
   * Executes a [BoundStatement] query and passes the [ResultSet] to the [assertion] block.
   *
   * @param statement The prepared [BoundStatement] to execute
   * @param assertion A block that receives the [ResultSet] for assertions
   * @return This [CassandraSystem] for chaining
   */
  suspend fun shouldQuery(
    statement: BoundStatement,
    assertion: (ResultSet) -> Unit
  ): CassandraSystem {
    report(action = "Query Cassandra (bound)") {
      val resultSet = cqlSession.execute(statement)
      assertion(resultSet)
      resultSet
    }
    return this
  }

  /**
   * Pauses the container. Use with care, as it will pause the container which might affect other tests.
   * This operation is not supported when using a provided instance.
   * @return CassandraSystem
   */
  suspend fun pause(): CassandraSystem {
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
   * @return CassandraSystem
   */
  suspend fun unpause(): CassandraSystem {
    report(action = "Unpause container") {
      withContainerOrWarn("unpause") { it.unpause() }
    }
    return this
  }

  private suspend fun obtainExposedConfiguration(): CassandraExposedConfiguration =
    when {
      context.options is ProvidedCassandraSystemOptions -> context.options.config
      context.runtime is StoveCassandraContainer -> startCassandraContainer(context.runtime)
      else -> throw UnsupportedOperationException("Unsupported runtime type: ${context.runtime::class}")
    }

  private suspend fun startCassandraContainer(container: StoveCassandraContainer): CassandraExposedConfiguration =
    state.capture {
      container.start()
      CassandraExposedConfiguration(
        host = container.host,
        port = container.getMappedPort(CASSANDRA_PORT),
        datacenter = container.localDatacenter,
        keyspace = context.options.keyspace
      )
    }

  private fun createSession(config: CassandraExposedConfiguration): CqlSession = CqlSession
    .builder()
    .addContactPoint(InetSocketAddress(config.host, config.port))
    .withLocalDatacenter(config.datacenter)
    .build()

  private inline fun withContainerOrWarn(
    operation: String,
    action: (StoveCassandraContainer) -> Unit
  ): CassandraSystem = when (val runtime = context.runtime) {
    is StoveCassandraContainer -> {
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

  private inline fun whenContainer(action: (StoveCassandraContainer) -> Unit) {
    if (context.runtime is StoveCassandraContainer) {
      action(context.runtime)
    }
  }

  private suspend fun runMigrationsIfNeeded() {
    if (shouldRunMigrations()) {
      context.options.migrationCollection.run(CassandraMigrationContext(cqlSession, context.options))
    }
  }

  private fun shouldRunMigrations(): Boolean = when {
    context.options is ProvidedCassandraSystemOptions -> context.options.runMigrations
    context.runtime is StoveCassandraContainer -> !state.isSubsequentRun() || stove.options.runMigrationsAlways
    else -> throw UnsupportedOperationException("Unsupported runtime type: ${context.runtime::class}")
  }

  companion object {
    const val CASSANDRA_PORT = 9042

    /**
     * Exposes the [CqlSession] to the [CassandraSystem].
     * Use this for advanced Cassandra operations not covered by the DSL.
     */
    fun CassandraSystem.session(): CqlSession = cqlSession
  }
}
