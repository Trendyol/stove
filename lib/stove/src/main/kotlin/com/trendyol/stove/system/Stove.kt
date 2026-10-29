package com.trendyol.stove.system

import arrow.core.*
import com.trendyol.stove.reporting.*
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

  private val cleanup = mutableListOf<AutoCloseable>()
  private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
  private var applicationStartAttempted = false

  @PublishedApi
  internal val activeSystems: MutableMap<KClass<*>, PluggedSystem> = mutableMapOf()

  @PublishedApi
  internal val keyedSystems: MutableMap<Pair<KClass<*>, SystemKey>, PluggedSystem> = mutableMapOf()

  private lateinit var applicationRunner: ApplicationUnderTest<*>
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  @PublishedApi
  internal val options: StoveOptions = optionsDsl.options

  internal val reporter: StoveReporter = StoveReporter(isEnabled = options.reportingEnabled)

  /**
   * Returns all registered systems from both default and keyed registrations.
   * This is the single source of truth used by lifecycle, reporting, and cleanup.
   */
  fun allRegisteredSystems(): Collection<PluggedSystem> =
    activeSystems.values + keyedSystems.values

  /**
   * Returns all registered systems that implement the given type.
   * Includes both default and keyed systems.
   */
  inline fun <reified T> systemsOf(): List<T> =
    allRegisteredSystems().filterIsInstance<T>()

  /**
   * Returns a read-only snapshot of all registered systems.
   */
  fun allSystems(): Collection<PluggedSystem> =
    allRegisteredSystems()

  /**
   * Whether dependencies (containers) should be kept running after tests complete.
   */
  val keepDependenciesRunning: Boolean
    get() = options.keepDependenciesRunning

  /**
   * Whether migrations should always run, even when reusing containers.
   */
  val runMigrationsAlways: Boolean
    get() = options.runMigrationsAlways

  /**
   * Creates a state storage for the given system and configuration types.
   */
  inline fun <reified TState : ExposedConfiguration, reified TSystem : PluggedSystem> createStateStorage(): StateStorage<TState> =
    options.createStateStorage<TState, TSystem>()

  /**
   * Creates a keyed state storage for the given system and configuration types.
   * The key name is included in the storage path to prevent collisions.
   */
  inline fun <reified TState : ExposedConfiguration, reified TSystem : PluggedSystem> createStateStorage(
    key: SystemKey
  ): StateStorage<TState> =
    options.stateStorageFactory.createWithKey(options, TSystem::class, TState::class, keyDisplayName(key))

  /**
   * Creates a state storage with an optional key name for disambiguation.
   * When keyName is null, behaves identically to the no-arg version.
   */
  inline fun <reified TState : ExposedConfiguration, reified TSystem : PluggedSystem> createStateStorage(
    keyName: String?
  ): StateStorage<TState> =
    if (keyName != null) {
      options.stateStorageFactory.createWithKey(options, TSystem::class, TState::class, keyName)
    } else {
      options.createStateStorage<TState, TSystem>()
    }

  /**
   * Registers a listener to receive report events.
   */
  fun addReportListener(listener: ReportEventListener) =
    reporter.addListener(listener)

  /**
   * Removes a previously registered report event listener.
   */
  fun removeReportListener(listener: ReportEventListener) =
    reporter.removeListener(listener)

  /**
   * Starts tracking a new test in the reporter.
   */
  fun startTest(ctx: StoveTestContext) =
    reporter.startTest(ctx)

  /**
   * Records a report entry in the current test.
   */
  fun recordReport(entry: ReportEntry) =
    reporter.record(entry)

  /**
   * Ends tracking the current test in the reporter.
   */
  fun endTest() =
    reporter.endTest()

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
    this.applicationRunner = applicationUnderTest
    return this
  }

  internal fun getSystemOrThrow(
    kClass: KClass<*>
  ): PluggedSystem = activeSystems[kClass]
    ?: error("System of type ${kClass.simpleName} is not registered in Stove")

  private lateinit var applicationUnderTestContext: Any

  /**
   * Starts each lifecycle phase in parallel, then exposes dependency configuration to the application.
   * A failed startup rolls back resources, including a partially started application.
   */
  override suspend fun run() {
    check(!closed.get()) { "Stove has been closed" }
    check(this::applicationRunner.isInitialized) { "Register an application runner before starting Stove" }
    try {
      startRegisteredSystems()
      instance = this
    } catch (failure: Throwable) {
      rollbackStartup(failure)
      throw failure
    }
  }

  private suspend fun startRegisteredSystems(): Unit = coroutineScope {
    val systems = allRegisteredSystems()
    systems.filterIsInstance<BeforeRunAware>().runInParallel { it.beforeRun() }
    systems.filterIsInstance<RunAware>().runInParallel { it.run() }
    startApplication(systems)
    systems.filterIsInstance<AfterRunAware>().runInParallel { it.afterRun() }
  }

  private suspend fun <T> Iterable<T>.runInParallel(action: suspend (T) -> Unit): Unit = coroutineScope {
    map { system -> async(Dispatchers.IO) { action(system) } }.awaitAll()
  }

  private suspend fun startApplication(systems: Collection<PluggedSystem>) {
    val configurations = systems.filterIsInstance<ExposesConfiguration>().flatMap { it.configuration() }
    // Own shutdown before user startup: startup and afterRun may partially succeed then fail.
    applicationStartAttempted = true
    applicationUnderTestContext = applicationRunner.start(configurations)
  }

  private suspend fun rollbackStartup(failure: Throwable): Unit = withContext(NonCancellable) {
    try {
      shutdown()
    } catch (cleanupFailure: Throwable) {
      if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
    }
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
   * Gets or registers a keyed [PluggedSystem]. Multiple instances of the same system type
   * can coexist when registered with different [SystemKey]s.
   *
   * @see SystemKey
   */
  inline fun <reified T : PluggedSystem> getOrRegister(
    key: SystemKey,
    system: T
  ): T = keyedSystems.getOrPut(T::class to key) {
    registerForDispose(system)
  } as T

  /**
   * Gets the registered system or returns [None]
   */
  inline fun <reified T : PluggedSystem> getOrNone(): Option<T> = activeSystems.getOrNone(T::class).map { it as T }

  /**
   * Gets the keyed registered system or returns [None]
   */
  inline fun <reified T : PluggedSystem> getOrNone(key: SystemKey): Option<T> =
    keyedSystems.getOrNone(T::class to key).map { it as T }

  fun <T : AutoCloseable> registerForDispose(closeable: T): T {
    check(!closed.get()) { "Cannot register resources on a closed Stove" }
    cleanup.add(closeable)
    return closeable
  }

  @Suppress("UNCHECKED_CAST", "unused")
  fun <TContext> applicationUnderTestContext(): TContext = applicationUnderTestContext as TContext

  override fun close(): Unit = runBlocking { shutdown() }

  private suspend fun shutdown() {
    if (!closed.compareAndSet(false, true)) return
    closeAll(takeShutdownActions()) { it() }
  }

  private fun takeShutdownActions(): List<suspend () -> Unit> = buildList {
    val resources = cleanup.filterIsInstance<ApplicationResource>()
    resources.forEach { resource -> add { resource.beforeApplicationStop() } }
    if (applicationStartAttempted) {
      applicationStartAttempted = false
      add { applicationRunner.stop() }
    }
    resources.asReversed().forEach { resource -> add { resource.close() } }
    cleanup.asReversed().filterNot { it is ApplicationResource }.forEach { resource -> add { resource.close() } }
    cleanup.clear()
    add { dumpFailureReport() }
  }

  private fun dumpFailureReport() {
    if (!options.dumpReportOnStop || !options.reportingEnabled) return
    val report = reporter.dumpIfFailed(options.defaultRenderer)
    if (report.isNotEmpty()) logger.info("=== Stove Test Report (Failures Detected) ===\n$report")
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
suspend fun stove(
  validation: @StoveDsl suspend ValidationDsl.() -> Unit
) {
  check(Stove.instanceInitialized()) { "Stove is not initialized yet, do not forget to call Stove#run" }
  validation(ValidationDsl(instance))
}
