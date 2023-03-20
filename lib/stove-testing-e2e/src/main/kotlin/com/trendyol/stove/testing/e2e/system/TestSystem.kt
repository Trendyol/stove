package com.trendyol.stove.testing.e2e.system

import arrow.core.None
import arrow.core.Option
import arrow.core.getOrNone
import com.trendyol.stove.functional.Try
import com.trendyol.stove.functional.recover
import com.trendyol.stove.testing.e2e.system.abstractions.*
import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

/**
 * Entrance of entire TestSystem.
 * Expects an url and port combination that are available also in the configuration of System Under Test.
 * For example; if your Spring application starts at :8081 then you need to change [baseUrl] to `http://localhost:8081`
 *
 * TestSystem should be initialized only once for project, because it will start all the dependencies you plugged into it.
 * See also: [PluggedSystem]
 *
 * As a full example of TestSystem:
 * ```kotlin
 * TestSystem(baseUrl = "http://localhost:8001")
 *     .withDefaultHttp()
 *     .withCouchbase(bucket = "Stove") { cfg -> listOf("couchbase.hosts=${cfg.hostsWithPort}") }
 *     .withKafka(
 *         configureExposedConfiguration = { cfg ->
 *             listOf("kafka.bootstrapServers=${cfg.boostrapServers}")
 *         }
 *     )
 *     .withWireMock(
 *         port = 9090,
 *         WireMockSystemOptions(
 *             removeStubAfterRequestMatched = true,
 *             afterRequest = { e, _, _ ->
 *                 logger.info(e.request.toString())
 *             }
 *         )
 *     )
 *     .systemUnderTest(
 *         runner = { parameters ->
 *             stove.spring.example.run(parameters) { it.addTestSystemDependencies() }
 *         },
 *         withParameters =
 *         listOf(
 *             "server.port=8001",
 *             "logging.level.root=warn",
 *             "logging.level.org.springframework.web=warn",
 *             "spring.profiles.active=default",
 *             "kafka.heartbeatInSeconds=2"
 *         )
 *     )
 * ```
 */
class TestSystem(
    val baseUrl: String = "http://localhost:8001",
    configure: TestSystemOptionsDsl.() -> Unit = {},
) : ReadyTestSystem, AutoCloseable {
    private val optionsDsl: TestSystemOptionsDsl = TestSystemOptionsDsl()

    init {
        configure(optionsDsl)
    }

    private var cleanup: MutableList<(suspend () -> Unit)> = mutableListOf()
    val activeSystems: MutableMap<KClass<*>, PluggedSystem> = mutableMapOf()
    private lateinit var applicationUnderTest: ApplicationUnderTest<*>
    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    val options: TestSystemOptions = optionsDsl.options

    companion object {
        /**
         * [instance] is created only once per project, and it is available throughout the lifetime of the all the tests.
         * DO NOT access it before [run] completes
         */
        lateinit var instance: TestSystem
    }

    /**
     * Application under test, the tests run against the application provided.
     * Usually a spring or generic application that can be hosted
     */
    fun applicationUnderTest(applicationUnderTest: ApplicationUnderTest<*>): TestSystem {
        this.applicationUnderTest = applicationUnderTest
        return this
    }

    private lateinit var applicationUnderTestContext: Any

    /**
     * Runs the entire dependency tree that implements [RunnableSystemWithContext] since only the [RunnableSystemWithContext] can be run.
     * Note that all the dependencies will run as parallel. It will invoke the runnable methods of [RunnableSystemWithContext]s with the order:
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

            val dependencyConfigurations = activeSystems
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
     * Gets or registers a [PluggedSystem] to the TestSystem. Use it when you want to register a new [PluggedSystem] to the TestSystem.
     * That can be a system that comply your needs, for example; SchedulerSystem, GarbageCollectorSystem etc... These are only the names,
     * so, you can implement these systems and register to the Test suite. When you register a new system to the test suite, it is wise to
     * implement [AfterRunAwareWithContext.afterRun] to get the context/container of the system, so you can create your system methods based on that.
     *
     * Example:
     * ```kotlin
     * // plug the new system called scheduler
     * TestSystem().withScheduler()
     *
     * // use it in testing
     * testSystem.scheduler().advance()
     * ```
     */
    inline fun <reified T : PluggedSystem> getOrRegister(system: T): T {
        return activeSystems.getOrPut(T::class) { registerForDispose(system) } as T
    }

    /**
     * Gets the registered system or returns [None]
     */
    inline fun <reified T : PluggedSystem> getOrNone(): Option<T> {
        return activeSystems.getOrNone(T::class).map { it as T }
    }

    fun <T : AutoCloseable> registerForDispose(closeable: T): T {
        cleanup.add { closeable.close() }
        return closeable
    }

    @Suppress("UNCHECKED_CAST")
    fun <TContext> applicationUnderTestContext(): TContext = applicationUnderTestContext as TContext

    override fun close(): Unit =
        runBlocking {
            Try { cleanup.forEach { it() } }
                .recover { logger.warn("got an error while stopping the TestSystem: ${it.message}") }
        }
}
