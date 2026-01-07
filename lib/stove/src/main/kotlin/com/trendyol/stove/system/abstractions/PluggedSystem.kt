package com.trendyol.stove.system.abstractions

import com.trendyol.stove.system.Stove

/**
 * Base interface for all systems that can be plugged into [Stove].
 *
 * A PluggedSystem represents a testable component such as:
 * - **Databases**: PostgreSQL, MongoDB, Couchbase, Elasticsearch, MSSQL, Redis
 * - **Message Brokers**: Kafka
 * - **HTTP**: HTTP client, WireMock
 * - **Bridge**: Access to the application's DI container
 * - **Custom Systems**: Any domain-specific system you implement
 *
 * ## Implementing a Custom PluggedSystem
 *
 * To create a custom system, implement this interface along with the appropriate
 * lifecycle interfaces ([RunAware], [AfterRunAware], [ExposesConfiguration]):
 *
 * ```kotlin
 * class MyCustomSystem(
 *     override val stove: Stove,
 *     private val options: MyCustomSystemOptions
 * ) : PluggedSystem, RunAware, AfterRunAware {
 *
 *     private lateinit var client: MyClient
 *
 *     override suspend fun run() {
 *         // Initialize your system (e.g., start container, connect to service)
 *         client = MyClient(options.connectionString)
 *     }
 *
 *     override suspend fun afterRun() {
 *         // Called after the application under test has started
 *         // Useful for setup that requires the app to be running
 *     }
 *
 *     override fun close() {
 *         // Cleanup resources
 *         client.close()
 *     }
 *
 *     // Chainable method for DSL
 *     override fun then(): Stove = stove
 *
 *     // Custom DSL methods
 *     fun doSomething(): MyCustomSystem {
 *         client.execute()
 *         return this
 *     }
 * }
 * ```
 *
 * ## Registering Your System
 *
 * Create extension functions for easy DSL usage:
 *
 * ```kotlin
 * // Registration function
 * fun WithDsl.myCustomSystem(
 *     configure: () -> MyCustomSystemOptions
 * ): Stove = stove.getOrRegister(
 *     MyCustomSystem(stove, configure())
 * ).let { stove }
 *
 * // Validation DSL function
 * suspend fun ValidationDsl.myCustom(
 *     block: suspend MyCustomSystem.() -> Unit
 * ) = block(stove.getOrNone<MyCustomSystem>().getOrElse {
 *     throw SystemNotRegisteredException(MyCustomSystem::class)
 * })
 * ```
 *
 * @see Stove
 * @see RunAware
 * @see AfterRunAware
 * @see ExposesConfiguration
 * @see ThenSystemContinuation
 * @author Oguzhan Soykan
 */
interface PluggedSystem :
  AutoCloseable,
  ThenSystemContinuation
