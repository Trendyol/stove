package com.trendyol.stove.testing.e2e.system.abstractions

import com.trendyol.stove.functional.Try
import com.trendyol.stove.functional.recover
import com.trendyol.stove.testing.e2e.system.TestSystem
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Lifecycle interface for systems that need to perform setup before starting.
 *
 * Implement this when your system needs to prepare resources before the main
 * [RunAware.run] phase. This is called before any systems are started.
 *
 * ## Example Use Cases
 * - Pulling Docker images ahead of time
 * - Validating configuration
 * - Creating network resources
 *
 * ```kotlin
 * class MySystem(...) : PluggedSystem, BeforeRunAware, RunAware {
 *     override suspend fun beforeRun() {
 *         // Download required files, validate config, etc.
 *         validateConfiguration()
 *     }
 *
 *     override suspend fun run() {
 *         // Start the actual system
 *     }
 * }
 * ```
 *
 * @see RunAware
 * @see AfterRunAware
 * @author Oguzhan Soykan
 */
interface BeforeRunAware {
  /**
   * Called before any systems are started.
   *
   * Use this for early initialization that doesn't depend on other systems.
   */
  suspend fun beforeRun()
}

/**
 * Core lifecycle interface for systems that can be started and stopped.
 *
 * This is the main lifecycle interface for [PluggedSystem]s. Most systems
 * implement this to start containers, establish connections, or initialize resources.
 *
 * ## Lifecycle Order
 *
 * 1. [BeforeRunAware.beforeRun] - All systems (parallel)
 * 2. [RunAware.run] - All systems (parallel)
 * 3. Application under test starts
 * 4. [AfterRunAware.afterRun] - All systems (parallel)
 *
 * ## Example
 *
 * ```kotlin
 * class PostgresqlSystem(...) : PluggedSystem, RunAware {
 *     private lateinit var container: PostgreSQLContainer<*>
 *
 *     override suspend fun run() {
 *         container = PostgreSQLContainer("postgres:15")
 *             .withDatabaseName("test")
 *         container.start()
 *     }
 *
 *     override suspend fun stop() {
 *         container.stop()
 *     }
 * }
 * ```
 *
 * @see BeforeRunAware
 * @see AfterRunAware
 * @author Oguzhan Soykan
 */
interface RunAware {
  /**
   * Starts the system.
   *
   * This is called in parallel for all registered systems.
   * Start containers, establish connections, or initialize resources here.
   */
  suspend fun run()

  /**
   * Stops the system.
   *
   * Called during [TestSystem] shutdown. Clean up resources here.
   */
  suspend fun stop()
}

/**
 * Lifecycle interface for systems that need the application context after startup.
 *
 * This is used by systems like [BridgeSystem] that need access to the
 * application's DI container after the application has started.
 *
 * ## Example
 *
 * ```kotlin
 * class SpringBridgeSystem(testSystem: TestSystem) :
 *     BridgeSystem<ApplicationContext>(testSystem) {
 *
 *     override suspend fun afterRun(context: ApplicationContext) {
 *         // Now we have access to Spring's ApplicationContext
 *         this.ctx = context
 *     }
 *
 *     override fun <D : Any> get(klass: KClass<D>): D =
 *         ctx.getBean(klass.java)
 * }
 * ```
 *
 * @param TContext The type of application context (e.g., `ApplicationContext` for Spring)
 * @see AfterRunAware
 * @see BridgeSystem
 * @author Oguzhan Soykan
 */
interface AfterRunAwareWithContext<TContext> {
  /**
   * Called after the application under test has started.
   *
   * @param context The application context from the started application.
   */
  suspend fun afterRun(context: TContext)
}

/**
 * Lifecycle interface for systems that need to perform actions after the application starts.
 *
 * Implement this when your system needs to do something after the application
 * under test is running, but doesn't need direct access to the application context.
 *
 * ## Example Use Cases
 * - Running database migrations
 * - Seeding test data
 * - Verifying connectivity
 *
 * ```kotlin
 * class PostgresqlSystem(...) : PluggedSystem, RunAware, AfterRunAware {
 *     override suspend fun afterRun() {
 *         // Run migrations after app has started
 *         migrations.forEach { it.execute(connection) }
 *     }
 * }
 * ```
 *
 * @see AfterRunAwareWithContext
 * @see RunAware
 * @author Oguzhan Soykan
 */
interface AfterRunAware {
  /**
   * Called after the application under test has started.
   */
  suspend fun afterRun()
}

/**
 * Combined lifecycle interface for systems with full lifecycle support and context access.
 *
 * This interface combines [BeforeRunAware], [RunAware], and [AfterRunAwareWithContext]
 * for systems that need complete lifecycle control and access to the application context.
 *
 * Most systems don't need this full interface; use individual interfaces instead.
 *
 * @param TContext The type of application context.
 * @see BeforeRunAware
 * @see RunAware
 * @see AfterRunAwareWithContext
 * @author Oguzhan Soykan
 */
interface RunnableSystemWithContext<TContext> :
  AutoCloseable,
  BeforeRunAware,
  RunAware,
  AfterRunAwareWithContext<TContext> {
  private val logger: Logger get() = LoggerFactory.getLogger(javaClass)

  override fun close(): Unit = runBlocking { Try { stop() }.recover { logger.warn("got an error while stopping") } }
}
