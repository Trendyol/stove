package com.trendyol.stove.testing.e2e.system.abstractions

import com.trendyol.stove.testing.e2e.system.TestSystem

/**
 * Interface representing the application being tested by Stove.
 *
 * This is the entry point for your actual application. Stove starts this application
 * after all test infrastructure (databases, message brokers, etc.) is running,
 * passing the exposed configurations so your app can connect to the test infrastructure.
 *
 * ## Framework Implementations
 *
 * Stove provides implementations for popular frameworks:
 * - **Spring Boot**: `SpringApplicationUnderTest`
 * - **Ktor**: `KtorApplicationUnderTest`
 * - **Micronaut**: `MicronautApplicationUnderTest`
 *
 * ## Spring Boot Example
 *
 * ```kotlin
 * TestSystem()
 *     .with {
 *         postgresql { /* config */ }
 *         kafka { /* config */ }
 *
 *         springBoot(
 *             runner = { params ->
 *                 com.example.MyApplication.run(params) {
 *                     addTestDependencies()  // Optional test-specific beans
 *                 }
 *             },
 *             withParameters = listOf(
 *                 "server.port=8080",
 *                 "logging.level.root=WARN"
 *             )
 *         )
 *     }
 *     .run()
 * ```
 *
 * ## Ktor Example
 *
 * ```kotlin
 * TestSystem()
 *     .with {
 *         mongodb { /* config */ }
 *
 *         ktor(
 *             withParameters = listOf("port=8080"),
 *             runner = { params ->
 *                 com.example.main(params) {
 *                     addTestModules()
 *                 }
 *             }
 *         )
 *     }
 *     .run()
 * ```
 *
 * ## Configuration Flow
 *
 * 1. [TestSystem] starts all [PluggedSystem]s
 * 2. Systems expose their configuration via [ExposesConfiguration]
 * 3. Configurations are collected and passed to [start]
 * 4. Your application starts with access to test infrastructure
 * 5. [AfterRunAware.afterRun] is called on all systems
 *
 * @param TContext The application context type (e.g., `ApplicationContext` for Spring,
 *                 `Application` for Ktor).
 * @see TestSystem
 * @see ExposesConfiguration
 * @author Oguzhan Soykan
 */
interface ApplicationUnderTest<TContext : Any> {
  /**
   * Starts the application with the provided configurations.
   *
   * The [configurations] list contains properties from all [ExposesConfiguration]
   * implementors plus any parameters specified in the test setup. These are typically
   * passed as system properties or command-line arguments.
   *
   * ## Example Configuration List
   *
   * ```kotlin
   * // configurations parameter might contain:
   * listOf(
   *     "spring.datasource.url=jdbc:postgresql://localhost:32789/test",
   *     "spring.datasource.username=test",
   *     "spring.kafka.bootstrap-servers=localhost:32790",
   *     "server.port=8080"
   * )
   * ```
   *
   * @param configurations Combined list of infrastructure configs and test parameters.
   * @return The application context for use by [AfterRunAwareWithContext] systems.
   */
  suspend fun start(configurations: List<String>): TContext

  /**
   * Stops the application gracefully.
   *
   * Called during [TestSystem] shutdown to clean up application resources.
   */
  suspend fun stop()
}
