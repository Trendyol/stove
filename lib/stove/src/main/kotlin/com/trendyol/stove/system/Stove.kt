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

  fun allRegisteredSystems(): Collection<PluggedSystem> = activeSystems.values + keyedSystems.values
  inline fun <reified T> systemsOf(): List<T> = allRegisteredSystems().filterIsInstance<T>()
  fun allSystems(): Collection<PluggedSystem> = allRegisteredSystems()

  /** Captures registration metadata once, after configuring this Stove. */
  fun runComponents(): List<RunComponent> = environment.capture(
    activeSystems.values,
    keyedSystems.map { (registration, system) -> registration.second to system },
    lifecycle.application
  )
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

  /** Connects registered sources for observers that run without starting the application. */
  fun startObserving(): StoveEvents = events.also { eventBus.connect(this) }

  /** Captures unfinished test evidence before an observer or system is closed. */
  fun capturePendingSnapshots() = eventBus.capturePendingSnapshots()

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
      return instance.activeSystems.getOrNone(kClass).map { it as T }
    }

    fun stop(): Unit = instance.close()
  }

  fun applicationUnderTest(applicationUnderTest: ApplicationUnderTest<*>): Stove = apply {
    lifecycle.application = Some(applicationUnderTest)
  }

  internal fun getSystemOrThrow(kClass: KClass<*>): PluggedSystem = activeSystems[kClass]
    ?: error("System of type ${kClass.simpleName} is not registered in Stove")

  override suspend fun run() {
    lifecycle.run()
    instance = this
  }

  fun with(withDsl: WithDsl.() -> Unit): Stove = apply { withDsl(WithDsl(this)) }

  inline fun <reified T : PluggedSystem> getOrRegister(system: T): T = activeSystems.getOrPut(T::class) {
    registerForDispose(system)
  } as T

  inline fun <reified T : PluggedSystem> getOrRegister(key: SystemKey, system: T): T =
    keyedSystems.getOrPut(T::class to key) { registerForDispose(system) } as T

  inline fun <reified T : PluggedSystem> getOrNone(): Option<T> = activeSystems.getOrNone(T::class).map { it as T }
  inline fun <reified T : PluggedSystem> getOrNone(key: SystemKey): Option<T> =
    keyedSystems.getOrNone(T::class to key).map { it as T }

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

  /** Teardown failures are reported and logged without changing a passing test's outcome. */
  override fun close(): Unit = runBlocking {
    runCatching { lifecycle.shutdown() }.onFailure { logger.warn("got an error while stopping Stove: ${it.message}", it) }
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
