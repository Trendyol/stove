@file:Suppress("unused")

package com.trendyol.stove.testing.e2e.rdbms.postgres

import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.rdbms.*
import com.trendyol.stove.testing.e2e.reporting.Reports
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import kotlinx.coroutines.runBlocking
import kotliquery.*
import org.slf4j.*

/**
 * PostgreSQL database system for testing relational data operations.
 *
 * Provides a DSL for testing PostgreSQL operations:
 * - SQL query execution with typed results
 * - DDL/DML statement execution
 * - Schema migrations
 * - Container pause/unpause for fault injection
 *
 * ## Querying Data
 *
 * ```kotlin
 * postgresql {
 *     // Query with row mapping
 *     shouldQuery(
 *         query = "SELECT id, name, email FROM users WHERE status = 'active'",
 *         mapper = { row ->
 *             User(
 *                 id = row.long("id"),
 *                 name = row.string("name"),
 *                 email = row.string("email")
 *             )
 *         }
 *     ) { users ->
 *         users.size shouldBeGreaterThan 0
 *         users.all { it.email.contains("@") } shouldBe true
 *     }
 * }
 * ```
 *
 * ## Executing SQL
 *
 * ```kotlin
 * postgresql {
 *     // Execute DML/DDL statements
 *     shouldExecute("INSERT INTO users (name, email) VALUES ('John', 'john@example.com')")
 *     shouldExecute("UPDATE users SET status = 'active' WHERE id = 123")
 *     shouldExecute("DELETE FROM users WHERE id = 123")
 *
 *     // Create tables
 *     shouldExecute("""
 *         CREATE TABLE IF NOT EXISTS orders (
 *             id SERIAL PRIMARY KEY,
 *             user_id BIGINT REFERENCES users(id),
 *             total DECIMAL(10,2),
 *             created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
 *         )
 *     """)
 * }
 * ```
 *
 * ## Fault Injection Testing
 *
 * Test application behavior during database outages:
 *
 * ```kotlin
 * postgresql {
 *     // Pause the database container
 *     pause()
 * }
 *
 * // Test application behavior during outage
 * http {
 *     getResponse("/api/health") { response ->
 *         response.status shouldBe 503
 *     }
 * }
 *
 * postgresql {
 *     // Resume the database
 *     unpause()
 * }
 *
 * // Verify recovery
 * http {
 *     getResponse("/api/health") { response ->
 *         response.status shouldBe 200
 *     }
 * }
 * ```
 *
 * ## Test Workflow Example
 *
 * ```kotlin
 * test("should create order and store in database") {
 *     TestSystem.validate {
 *         // Create order via API
 *         http {
 *             postAndExpectBody<OrderResponse>(
 *                 uri = "/orders",
 *                 body = CreateOrderRequest(userId = 123, amount = 99.99).some()
 *             ) { response ->
 *                 response.status shouldBe 201
 *             }
 *         }
 *
 *         // Verify in database
 *         postgresql {
 *             shouldQuery(
 *                 query = "SELECT * FROM orders WHERE user_id = 123",
 *                 mapper = { row -> Order(row.long("id"), row.decimal("total")) }
 *             ) { orders ->
 *                 orders shouldHaveSize 1
 *                 orders.first().total shouldBe BigDecimal("99.99")
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * ## Configuration
 *
 * ```kotlin
 * TestSystem()
 *     .with {
 *         postgresql {
 *             PostgresqlOptions(
 *                 configureExposedConfiguration = { cfg ->
 *                     listOf(
 *                         "spring.datasource.url=${cfg.jdbcUrl}",
 *                         "spring.datasource.username=${cfg.username}",
 *                         "spring.datasource.password=${cfg.password}"
 *                     )
 *                 }
 *             ).migrations {
 *                 register<CreateTablesSchema>()
 *                 register<SeedTestData>()
 *             }
 *         }
 *     }
 * ```
 *
 * @property testSystem The parent test system.
 * @see PostgresqlOptions
 * @see RelationalDatabaseExposedConfiguration
 */
@StoveDsl
class PostgresqlSystem internal constructor(
  override val testSystem: TestSystem,
  private val postgresContext: PostgresqlContext
) : PluggedSystem,
  RunAware,
  ExposesConfiguration,
  Reports {
  @PublishedApi
  internal lateinit var sqlOperations: NativeSqlOperations

  override val reportSystemName: String = "PostgreSQL"

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
  suspend inline fun <reified T : Any> shouldQuery(
    query: String,
    crossinline mapper: (Row) -> T,
    crossinline assertion: (List<T>) -> Unit
  ): PostgresqlSystem {
    val results = sqlOperations.select(query) { mapper(it) }
    recordAndExecute(
      action = "Query",
      input = query.trim(),
      output = "${results.size} row(s) returned",
      metadata = mapOf("rowCount" to results.size),
      expected = "Assertion passed",
      actual = results
    ) {
      assertion(results)
    }
    return this
  }

  @StoveDsl
  fun shouldExecute(sql: String): PostgresqlSystem {
    val affectedRows = sqlOperations.execute(sql)

    recordAction(
      action = "Execute SQL",
      input = sql.trim(),
      output = "$affectedRows row(s) affected",
      metadata = mapOf("affectedRows" to affectedRows)
    )

    check(affectedRows >= 0) { "Failed to execute sql: $sql" }
    return this
  }

  /**
   * Pauses the container. Use with care, as it will pause the container which might affect other tests.
   * This operation is not supported when using a provided instance.
   * @return PostgresqlSystem
   */
  @StoveDsl
  fun pause(): PostgresqlSystem {
    recordAction(
      action = "Pause container",
      metadata = mapOf("operation" to "fault-injection")
    )
    return withContainerOrWarn("pause") { it.pause() }
  }

  /**
   * Unpauses the container. Use with care, as it will unpause the container which might affect other tests.
   * This operation is not supported when using a provided instance.
   * @return PostgresqlSystem
   */
  @StoveDsl
  fun unpause(): PostgresqlSystem {
    recordAction(
      action = "Unpause container"
    )
    return withContainerOrWarn("unpause") { it.unpause() }
  }

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
