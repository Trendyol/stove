package com.trendyol.stove.system.abstractions

/**
 * Interface for systems that expose configuration to the application under test.
 *
 * When a [PluggedSystem] starts (e.g., a database container), it knows its runtime configuration
 * (ports, credentials, URLs). This interface allows the system to expose that configuration
 * so it can be passed to the application under test during startup.
 *
 * ## How It Works
 *
 * 1. [TestSystem] starts all registered systems via [RunAware.run]
 * 2. After systems are running, [TestSystem] collects configuration from all [ExposesConfiguration] implementors
 * 3. The collected configuration is passed to [ApplicationUnderTest.start]
 * 4. Your application receives these as system properties/environment variables
 *
 * ## Example Implementation
 *
 * ```kotlin
 * class KafkaSystem(
 *     override val stove: Stove,
 *     private val options: KafkaSystemOptions
 * ) : PluggedSystem, RunAware, ExposesConfiguration {
 *
 *     private lateinit var container: KafkaContainer
 *     private lateinit var exposedConfig: KafkaExposedConfiguration
 *
 *     override suspend fun run() {
 *         container = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"))
 *         container.start()
 *         exposedConfig = KafkaExposedConfiguration(
 *             bootstrapServers = container.bootstrapServers
 *         )
 *     }
 *
 *     override fun configuration(): List<String> =
 *         options.configureExposedConfiguration(exposedConfig)
 *     // Returns: ["spring.kafka.bootstrap-servers=localhost:32789"]
 * }
 * ```
 *
 * ## Configuration Format
 *
 * The returned list typically contains `key=value` strings that match your application's
 * expected configuration format:
 *
 * ```kotlin
 * // Spring Boot style
 * listOf(
 *     "spring.datasource.url=jdbc:postgresql://localhost:5432/test",
 *     "spring.datasource.username=test"
 * )
 *
 * // Generic style
 * listOf(
 *     "DATABASE_URL=jdbc:postgresql://localhost:5432/test",
 *     "DATABASE_USER=test"
 * )
 * ```
 *
 * @see PluggedSystem
 * @see RunAware
 * @see ConfiguresExposedConfiguration
 * @see ExposedConfiguration
 * @author Oguzhan Soykan
 */
interface ExposesConfiguration {
  /**
   * Returns the configuration properties exposed by this system.
   *
   * This method is called after [RunAware.run] completes, so containers
   * are running and their runtime information (ports, hosts) is available.
   *
   * @return A list of configuration strings, typically in `key=value` format.
   */
  fun configuration(): List<String>
}
