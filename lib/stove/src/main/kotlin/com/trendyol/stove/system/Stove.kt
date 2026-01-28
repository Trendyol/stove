package com.trendyol.stove.system

import arrow.core.*
import com.trendyol.stove.functional.*
import com.trendyol.stove.reporting.StoveReporter
import com.trendyol.stove.system.Stove.Companion.instance
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl
import kotlinx.coroutines.*
import org.slf4j.*
import kotlin.reflect.KClass

/**
 * Entrance of entire Stove test system.
 * Expects an url and port combination that are available also in the configuration of System Under Test.
 * For example; if your Spring application starts at :8081 then you need to change httpClient.baseUrl to `http://localhost:8081`
 *
 * Stove should be initialized only once for project, because it will start all the dependencies you plugged into it.
 * See also: [PluggedSystem]
 *
 * As a full example of Stove:
 * ```kotlin
 * Stove {
 *     if (this.isRunningLocally()) {
 *       enableReuseForTestContainers()
 *       keepDependenciesRunning()
 *     }
 *   }.with {
 *     httpClient {
 *       HttpClientSystemOptions(
 *         baseUrl = "http://localhost:8080",
 *       )
 *     }
 *     bridge()
 *     postgresql {
 *       PostgresqlOptions(configureExposedConfiguration = { cfg ->
 *         listOf(
 *           "database.jdbcUrl=${cfg.jdbcUrl}",
 *           "database.host=${cfg.host}",
 *           "database.port=${cfg.port}",
 *           "database.name=${cfg.database}",
 *           "database.username=${cfg.username}",
 *           "database.password=${cfg.password}"
 *         )
 *       })
 *     }
 *     kafka {
 *       stoveKafkaObjectMapperRef = objectMapperRef
 *       KafkaSystemOptions {
 *         listOf(
 *           "kafka.bootstrapServers=${it.bootstrapServers}",
 *           "kafka.interceptorClasses=${it.interceptorClass}"
 *         )
 *       }
 *     }
 *     wiremock {
 *       WireMockSystemOptions(
 *         port = 9090,
 *         removeStubAfterRequestMatched = true,
 *         afterRequest = { e, _ ->
 *           logger.info(e.request.toString())
 *         }
 *       )
 *     }
 *     ktor(
 *       withParameters = listOf(
 *         "port=8080"
 *       ),
 *       runner = { parameters ->
 *         stove.ktor.example.run(parameters) {
 *           addTestSystemDependencies()
 *         }
 *       }
 *     )
 *   }.run()
 * ```
 */
@StoveDsl
class Stove(
  configure: @StoveDsl StoveOptionsDsl.() -> Unit = {}
) : ReadyStove,
  AutoCloseable {
  private val optionsDsl: StoveOptionsDsl = StoveOptionsDsl()

  init {
    configure(optionsDsl)
  }

  private var cleanup: MutableList<(suspend () -> Unit)> = mutableListOf()
  val activeSystems: MutableMap<KClass<*>, PluggedSystem> = mutableMapOf()
  private lateinit var applicationUnderTest: ApplicationUnderTest<*>
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  val options: StoveOptions = optionsDsl.options
  val reporter: StoveReporter = StoveReporter(isEnabled = options.reportingEnabled)

  companion object {
    /**
     * [instance] is created only once per project, and it is available throughout the lifetime of the all the tests.
     * DO NOT access it before [run] completes
     */
    internal lateinit var instance: Stove

    /**
     * Check if Stove instance has been initialized.
     */
    fun instanceInitialized(): Boolean = ::instance.isInitialized

    fun reporter(): StoveReporter {
      check(::instance.isInitialized) { "Stove is not initialized yet, do not forget to call Stove#run" }
      return instance.reporter
    }

    fun options(): StoveOptions {
      check(::instance.isInitialized) { "Stove is not initialized yet, do not forget to call Stove#run" }
      return instance.options
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : PluggedSystem> getSystem(kClass: KClass<*>): T {
      check(::instance.isInitialized) { "Stove is not initialized yet, do not forget to call Stove#run" }
      return instance.getSystemOrThrow(kClass) as T
    }

    /**
     * Returns the system of the given type as an Option.
     * Returns None if Stove is not initialized or the system is not registered.
     */
    inline fun <reified T : PluggedSystem> getSystemOrNone(): Option<T> =
      getSystemOrNone(T::class)

    /**
     * Returns the system of the given type as an Option.
     * Returns None if Stove is not initialized or the system is not registered.
     */
    @Suppress("UNCHECKED_CAST")
    @PublishedApi
    internal fun <T : PluggedSystem> getSystemOrNone(kClass: KClass<T>): Option<T> {
      if (!::instance.isInitialized) return None
      return instance.activeSystems.getOrNone(kClass).map { it as T }
    }

    fun stop(): Unit = instance.close()
  }

  /**
   * Application under test, the tests run against the application provided.
   * Usually a spring or generic application that can be hosted
   */
  fun applicationUnderTest(applicationUnderTest: ApplicationUnderTest<*>): Stove {
    this.applicationUnderTest = applicationUnderTest
    return this
  }

  internal fun getSystemOrThrow(
    kClass: KClass<*>
  ): PluggedSystem = activeSystems[kClass]
    ?: error("System of type ${kClass.simpleName} is not registered in Stove")

  private lateinit var applicationUnderTestContext: Any

  /**
   * Runs the entire dependency tree that implements [RunnableSystemWithContext] since only the [RunnableSystemWithContext] can be run.
   * Note that all the dependencies will run as parallel.
   * It will invoke the runnable methods of [RunnableSystemWithContext]s with the order:
   * - [RunnableSystemWithContext.beforeRun]
   * - [RunnableSystemWithContext.run]
   * - [RunnableSystemWithContext.afterRun]
   */
  override suspend fun run() {
    coroutineScope {
      val beforeRunAwareSystems = activeSystems.map { it.value }.filterIsInstance<BeforeRunAware>()
      val runAwareSystems = activeSystems.map { it.value }.filterIsInstance<RunAware>()

      beforeRunAwareSystems.map { async(context = Dispatchers.IO) { it.beforeRun() } }.awaitAll()
      runAwareSystems.map { async(context = Dispatchers.IO) { it.run() } }.awaitAll()

      val dependencyConfigurations =
        activeSystems
          .map { it.value }
          .filterIsInstance<ExposesConfiguration>()
          .flatMap { it.configuration() }

      applicationUnderTestContext = applicationUnderTest.start(dependencyConfigurations)

      val afterRunAwareSystems = activeSystems.map { it.value }.filterIsInstance<AfterRunAware>()
      afterRunAwareSystems.map { async(context = Dispatchers.IO) { it.afterRun() } }.awaitAll()

      activeSystems.forEach { cleanup.add { it.value.close() } }
      cleanup.add { applicationUnderTest.stop() }
    }

    instance = this
  }

  /**
   * Enables the DSL for constructing the entire system with the [PluggedSystem]s.
   *
   * Example:
   * ```kotlin
   *  Stove().with {
   *    httpClient{
   *      // configure the http client
   *    }
   *    kafka{
   *      // configure kafka
   *    }
   *    couchbase {
   *      // configure couchbase
   *    }
   *
   *    // and so on...
   *  }
   * ```
   */
  @StoveDsl
  fun with(withDsl: WithDsl.() -> Unit): Stove {
    withDsl(WithDsl(this))
    return this
  }

  /**
   * Gets or registers a [PluggedSystem] to Stove. Use it when you want to register a new [PluggedSystem] to Stove.
   * That can be a system that comply your needs, for example; SchedulerSystem, GarbageCollectorSystem etc... These are only the names,
   * so, you can implement these systems and register to the Test suite. When you register a new system to the test suite, it is wise to
   * implement [AfterRunAwareWithContext.afterRun] to get the context/container of the system,
   * so you can create your system methods based on that.
   *
   * Example:
   * ```kotlin
   * // plug the new system called scheduler
   * Stove().withScheduler()
   *
   * // use it in testing
   * stove.scheduler().advance()
   * ```
   */
  inline fun <reified T : PluggedSystem> getOrRegister(system: T): T = activeSystems.getOrPut(T::class) {
    registerForDispose(system)
  } as T

  /**
   * Gets the registered system or returns [None]
   */
  inline fun <reified T : PluggedSystem> getOrNone(): Option<T> = activeSystems.getOrNone(T::class).map { it as T }

  fun <T : AutoCloseable> registerForDispose(closeable: T): T {
    cleanup.add { closeable.close() }
    return closeable
  }

  @Suppress("UNCHECKED_CAST", "unused")
  fun <TContext> applicationUnderTestContext(): TContext = applicationUnderTestContext as TContext

  override fun close(): Unit = runBlocking {
    Try {
      if (options.dumpReportOnStop && options.reportingEnabled) {
        // Only dump report if there are failures
        val report = reporter.dumpIfFailed(options.defaultRenderer)
        if (report.isNotEmpty()) {
          logger.info("=== Stove Test Report (Failures Detected) ===")
          logger.info(report)
        }
      }
      cleanup.forEach { it() }
    }.recover { logger.warn("got an error while stopping Stove: ${it.message}") }
  }
}

/**
 * Main entry point for test validation. Use this DSL to write assertions against your system.
 *
 * This is the primary way to interact with Stove in your tests. The DSL provides
 * access to all registered systems (HTTP, Kafka, databases, etc.) for assertions.
 *
 * ## Example
 *
 * ```kotlin
 * @Test
 * fun `should create user and publish event`() = runTest {
 *     stove {
 *         http {
 *             post<CreateUserRequest, UserResponse>("/users", request) { response ->
 *                 response.id shouldNotBe null
 *             }
 *         }
 *         kafka {
 *             shouldBePublished<UserCreatedEvent> {
 *                 actual.userId == expectedUserId
 *             }
 *         }
 *         couchbase {
 *             shouldGet<User>("users", "user-123") { user ->
 *                 user.name shouldBe "John"
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * @param validation The DSL block containing test assertions.
 * @throws IllegalStateException if Stove has not been initialized via [Stove.run].
 * @see ValidationDsl
 */
@StoveDsl
suspend fun stove(
  validation: @StoveDsl suspend ValidationDsl.() -> Unit
) {
  check(Stove.instanceInitialized()) { "Stove is not initialized yet, do not forget to call Stove#run" }
  validation(ValidationDsl(instance))
}
