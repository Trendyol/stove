package com.trendyol.stove.system

import arrow.core.*
import com.trendyol.stove.chaos.withChaosOwnership
import com.trendyol.stove.events.*
import com.trendyol.stove.reporting.*
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

/** Owner of the application, registered systems, and their observations. */
@StoveDsl
@Suppress("TooManyFunctions") // Root facade: every function is public API.
class Stove(configure: @StoveDsl StoveOptionsDsl.() -> Unit = {}) : ReadyStove, AutoCloseable {
  private val configuration = StoveOptionsDsl().apply(configure)

  @PublishedApi internal val options: StoveOptions = configuration.options

  @PublishedApi internal val activeSystems: MutableMap<KClass<*>, PluggedSystem> = mutableMapOf()

  @PublishedApi internal val keyedSystems: MutableMap<Pair<KClass<*>, SystemKey>, PluggedSystem> = mutableMapOf()
  internal val reporter = StoveReporter(isEnabled = options.reportingEnabled)
  private val eventBus = StoveEventBus(configuration.eventObservers.handlers)
  private val environment = RunEnvironment(eventBus::publish)
  val events = StoveEvents(eventBus.runId, eventBus.stream, environment.stream, eventBus::flush)
  private val lifecycle = StoveLifecycle(this, eventBus, environment)
  private val logger = LoggerFactory.getLogger(javaClass)

  fun allRegisteredSystems(): Collection<PluggedSystem> = lifecycle.registrations { activeSystems.values + keyedSystems.values }
  inline fun <reified T> systemsOf(): List<T> = allRegisteredSystems().filterIsInstance<T>()
  fun allSystems(): Collection<PluggedSystem> = allRegisteredSystems()

  /** Previews the current registrations until startup captures the run inventory. */
  fun runComponents(): List<RunComponent> = readRunComponents(freeze = false)

  internal fun readRunComponents(freeze: Boolean): List<RunComponent> {
    val (systems, keyed, application) = lifecycle.registrations {
      Triple(activeSystems.values.toList(), keyedSystems.map { (key, system) -> key.second to system }, lifecycle.application)
    }
    return environment.components(systems, keyed, application, freeze)
  }
  val keepDependenciesRunning: Boolean get() = options.keepDependenciesRunning
  val runMigrationsAlways: Boolean get() = options.runMigrationsAlways

  inline fun <reified TState : ExposedConfiguration, reified TSystem : PluggedSystem> createStateStorage(): StateStorage<TState> =
    options.createStateStorage<TState, TSystem>()

  inline fun <reified TState : ExposedConfiguration, reified TSystem : PluggedSystem> createStateStorage(
    key: SystemKey
  ): StateStorage<TState> = options.createStateStorage<TState, TSystem>(keyDisplayName(key))

  inline fun <reified TState : ExposedConfiguration, reified TSystem : PluggedSystem> createStateStorage(
    keyName: String?
  ): StateStorage<TState> = keyName.toOption().fold(
    { options.createStateStorage<TState, TSystem>() },
    { options.createStateStorage<TState, TSystem>(it) }
  )

  /** Publishes an observed fact through this Stove's ordered event pipeline. */
  fun publish(event: StoveEvent) = eventBus.publish(event)

  /** Connects current sources. Call again after registering more publishers, or start Stove to connect them. */
  fun startObserving(): StoveEvents = events.also { eventBus.connect(this) }

  /** Captures a checkpoint without ending any active tests. */
  fun capturePendingSnapshots() = eventBus.capturePendingSnapshots(consume = false)

  /** Rejects synchronous teardown or draining from this Stove's dispatch worker. */
  fun requireExternalEventCaller() = eventBus.requireExternalCaller()

  fun addReportListener(listener: ReportEventListener) = reporter.addListener(listener)
  fun removeReportListener(listener: ReportEventListener) = reporter.removeListener(listener)
  fun startTest(ctx: StoveTestContext) = reporter.startTest(ctx)
  fun recordReport(entry: ReportEntry) = reporter.record(entry)
  fun endTest() = reporter.endTest()

  companion object {
    internal lateinit var instance: Stove
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

    inline fun <reified T : PluggedSystem> getSystemOrNone(): Option<T> = getSystemOrNone(T::class)

    @Suppress("UNCHECKED_CAST")
    @PublishedApi
    internal fun <T : PluggedSystem> getSystemOrNone(kClass: KClass<T>): Option<T> {
      if (!::instance.isInitialized) return None
      val current = instance
      return current.lookup(current.activeSystems, kClass).map { it as T }
    }

    fun stop(): Unit = instance.close()
  }

  fun applicationUnderTest(applicationUnderTest: ApplicationUnderTest<*>): Stove = apply {
    lifecycle.configure(applicationUnderTest)
  }

  internal fun getSystemOrThrow(kClass: KClass<*>): PluggedSystem = lookup(activeSystems, kClass)
    .getOrElse { error("System of type ${kClass.simpleName} is not registered in Stove") }

  override suspend fun run() = lifecycle.run()

  fun with(withDsl: WithDsl.() -> Unit): Stove = apply { withDsl(WithDsl(this)) }

  /** Registers before startup. Call [startObserving] again to connect new publishers during standalone observation. */
  inline fun <reified T : PluggedSystem> getOrRegister(system: T): T = register(activeSystems, T::class, system)

  /** Keyed variant of [getOrRegister]; standalone observers reconnect via [startObserving]. */
  inline fun <reified T : PluggedSystem> getOrRegister(key: SystemKey, system: T): T = register(keyedSystems, T::class to key, system)

  @PublishedApi
  @Suppress("UNCHECKED_CAST")
  internal fun <K, T : PluggedSystem> register(systems: MutableMap<K, PluggedSystem>, key: K, system: T): T =
    lifecycle.registrations {
      systems.getOrPut(key) {
        lifecycle.requireConfigurable()
        registerForDispose(system)
      } as T
    }

  inline fun <reified T : PluggedSystem> getOrNone(): Option<T> = lookup(activeSystems, T::class).map { it as T }
  inline fun <reified T : PluggedSystem> getOrNone(key: SystemKey): Option<T> = lookup(keyedSystems, T::class to key).map { it as T }

  @PublishedApi
  internal fun <K> lookup(systems: Map<K, PluggedSystem>, key: K) = lifecycle.registrations { systems.getOrNone(key) }

  fun <T : AutoCloseable> registerForDispose(closeable: T): T = lifecycle.register(closeable)

  @Suppress("UNCHECKED_CAST", "unused")
  fun <TContext> applicationUnderTestContext(): TContext = lifecycle.context.getOrElse {
    error("Application context is unavailable before successful startup")
  } as TContext

  internal fun dumpFailureReport() {
    if (!options.dumpReportOnStop || !options.reportingEnabled) return
    val report = reporter.dumpIfFailed(options.defaultRenderer)
    if (report.isNotEmpty()) logger.info("=== Stove Test Report (Failures Detected) ===\n$report")
  }

  internal suspend fun shutdown() = lifecycle.shutdown()

  /** Teardown failures are reported and logged without changing a passing test's outcome. */
  override fun close() {
    lifecycle.requireExternalClose()
    runBlocking { runCatching { shutdown() }.onFailure { logger.warn("got an error while stopping Stove: ${it.message}", it) } }
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
 * Uses the instance bound to [StoveContext], falling back to the initialized suite instance.
 * The binding is inherited by child coroutines and nested validation helpers.
 *
 * @param validation The DSL block containing test assertions.
 * @throws IllegalStateException if Stove has not been initialized via [Stove.run].
 * @see ValidationDsl
 */
suspend fun stove(
  validation: @StoveDsl suspend ValidationDsl.() -> Unit
) {
  val context = currentCoroutineContext()[StoveContext] ?: StoveContext.capture()
  withContext(context) {
    context.stove.withChaosOwnership { validation(ValidationDsl(context.stove)) }
  }
}
