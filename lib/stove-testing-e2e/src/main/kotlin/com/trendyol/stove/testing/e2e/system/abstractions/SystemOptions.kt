package com.trendyol.stove.testing.e2e.system.abstractions

/**
 * Marker interface for system configuration options.
 *
 * Each [PluggedSystem] has its own options class implementing this interface.
 * Options define how the system should be configured, including container settings,
 * connection parameters, and configuration exposure.
 *
 * ## Example Implementation
 *
 * ```kotlin
 * data class PostgresqlOptions(
 *     val databaseName: String = "test_db",
 *     val username: String = "test",
 *     val password: String = "test",
 *     override val configureExposedConfiguration: (PostgresqlExposedConfiguration) -> List<String>
 * ) : SystemOptions, ConfiguresExposedConfiguration<PostgresqlExposedConfiguration>
 * ```
 *
 * ## Usage in TestSystem
 *
 * ```kotlin
 * TestSystem()
 *     .with {
 *         postgresql {
 *             PostgresqlOptions(
 *                 databaseName = "my_app_test",
 *                 configureExposedConfiguration = { cfg ->
 *                     listOf(
 *                         "spring.datasource.url=${cfg.jdbcUrl}",
 *                         "spring.datasource.username=${cfg.username}",
 *                         "spring.datasource.password=${cfg.password}"
 *                     )
 *                 }
 *             )
 *         }
 *     }
 * ```
 *
 * @see PluggedSystem
 * @see ExposedConfiguration
 * @see ConfiguresExposedConfiguration
 */
interface SystemOptions

/**
 * Marker interface for configuration values exposed by a [PluggedSystem] to the application under test.
 *
 * When a system starts (e.g., a PostgreSQL container), it exposes configuration values
 * like connection URLs, ports, and credentials. These values are passed to the application
 * under test so it can connect to the test infrastructure.
 *
 * ## Example Implementation
 *
 * ```kotlin
 * data class PostgresqlExposedConfiguration(
 *     val host: String,
 *     val port: Int,
 *     val database: String,
 *     val username: String,
 *     val password: String
 * ) : ExposedConfiguration {
 *     val jdbcUrl: String
 *         get() = "jdbc:postgresql://$host:$port/$database"
 * }
 * ```
 *
 * ## How Configuration Flows
 *
 * 1. System starts (container or provided instance)
 * 2. System creates [ExposedConfiguration] with runtime values
 * 3. [ConfiguresExposedConfiguration.configureExposedConfiguration] transforms it to property strings
 * 4. Properties are passed to the application under test on startup
 *
 * @see SystemOptions
 * @see ConfiguresExposedConfiguration
 * @see ExposesConfiguration
 */
interface ExposedConfiguration

/**
 * Interface for system options that can transform [ExposedConfiguration] into application properties.
 *
 * This interface bridges the gap between Stove's test infrastructure and your application's
 * configuration format (Spring properties, environment variables, etc.).
 *
 * ## Example
 *
 * ```kotlin
 * data class KafkaSystemOptions(
 *     override val configureExposedConfiguration: (KafkaExposedConfiguration) -> List<String>
 * ) : SystemOptions, ConfiguresExposedConfiguration<KafkaExposedConfiguration>
 *
 * // Usage
 * kafka {
 *     KafkaSystemOptions { cfg ->
 *         listOf(
 *             "spring.kafka.bootstrap-servers=${cfg.bootstrapServers}",
 *             "spring.kafka.consumer.group-id=test-group"
 *         )
 *     }
 * }
 * ```
 *
 * @param T The type of exposed configuration this options class works with.
 * @see ExposedConfiguration
 * @see SystemOptions
 */
interface ConfiguresExposedConfiguration<T : ExposedConfiguration> {
  /**
   * Function that transforms the exposed configuration into a list of property strings.
   *
   * The returned strings are typically in the format `key=value` and are passed
   * to the application under test as configuration properties.
   */
  val configureExposedConfiguration: (T) -> List<String>
}

/**
 * Interface for system options that connect to externally provided instances
 * instead of starting testcontainers.
 *
 * Use this when you want to:
 * - Connect to shared test infrastructure
 * - Use existing databases/services in CI/CD
 * - Debug against local installations
 * - Avoid container startup overhead
 *
 * ## Example
 *
 * ```kotlin
 * // Instead of starting a PostgreSQL container, connect to an existing instance
 * postgresql {
 *     ProvidedPostgresqlOptions(
 *         providedConfig = PostgresqlExposedConfiguration(
 *             host = "localhost",
 *             port = 5432,
 *             database = "test_db",
 *             username = "postgres",
 *             password = "secret"
 *         ),
 *         configureExposedConfiguration = { cfg ->
 *             listOf("spring.datasource.url=${cfg.jdbcUrl}")
 *         }
 *     )
 * }
 * ```
 *
 * @param TConfig The type of exposed configuration for this system.
 * @see SystemOptions
 * @see ExposedConfiguration
 */
interface ProvidedSystemOptions<TConfig : ExposedConfiguration> {
  /**
   * The configuration for the provided (external) instance.
   *
   * This contains connection details for the external service.
   * Unlike container-based options, this is always non-null.
   */
  val providedConfig: TConfig

  /**
   * Whether to run database migrations when using a provided instance.
   *
   * Set to `true` if you want Stove to apply migrations to the external database.
   * Set to `false` if migrations are managed externally or not needed.
   */
  val runMigrationsForProvided: Boolean
}
