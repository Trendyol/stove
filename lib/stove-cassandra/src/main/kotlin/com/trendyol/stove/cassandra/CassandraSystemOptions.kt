package com.trendyol.stove.cassandra

import com.datastax.oss.driver.api.core.CqlSession
import com.trendyol.stove.database.migrations.*
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl

/**
 * Context provided to Cassandra migrations.
 * Contains the CQL session and options for performing setup operations.
 *
 * @property session The CQL session for executing statements
 * @property options The Cassandra system options
 */
@StoveDsl
data class CassandraMigrationContext(
  val session: CqlSession,
  val options: CassandraSystemOptions
)

/**
 * Convenience type alias for Cassandra migrations.
 *
 * Instead of writing `DatabaseMigration<CassandraMigrationContext>`, use `CassandraMigration`:
 * ```kotlin
 * class MyMigration : CassandraMigration {
 *   override val order: Int = 1
 *   override suspend fun execute(connection: CassandraMigrationContext) { ... }
 * }
 * ```
 */
typealias CassandraMigration = DatabaseMigration<CassandraMigrationContext>

/**
 * Options for configuring the Cassandra system in container mode.
 */
@StoveDsl
open class CassandraSystemOptions(
  open val keyspace: String = "stove",
  open val datacenter: String = "datacenter1",
  open val container: CassandraContainerOptions = CassandraContainerOptions(),
  open val cleanup: suspend (CqlSession) -> Unit = {},
  override val configureExposedConfiguration: (CassandraExposedConfiguration) -> List<String>
) : SystemOptions,
  ConfiguresExposedConfiguration<CassandraExposedConfiguration>,
  SupportsMigrations<CassandraMigrationContext, CassandraSystemOptions> {
  override val migrationCollection: MigrationCollection<CassandraMigrationContext> = MigrationCollection()

  companion object {
    /**
     * Creates options configured to use an externally provided Cassandra instance
     * instead of a testcontainer.
     *
     * @param host The Cassandra host
     * @param port The Cassandra native transport port (default: 9042)
     * @param datacenter The local datacenter name (default: "datacenter1")
     * @param keyspace The default keyspace to use
     * @param runMigrations Whether to run migrations on the external instance (default: true)
     * @param cleanup A suspend function to clean up data after tests complete
     * @param configureExposedConfiguration Function to map exposed config to application properties
     */
    fun provided(
      host: String,
      port: Int = 9042,
      datacenter: String = "datacenter1",
      keyspace: String = "stove",
      runMigrations: Boolean = true,
      cleanup: suspend (CqlSession) -> Unit = {},
      configureExposedConfiguration: (CassandraExposedConfiguration) -> List<String>
    ): ProvidedCassandraSystemOptions = ProvidedCassandraSystemOptions(
      config = CassandraExposedConfiguration(
        host = host,
        port = port,
        datacenter = datacenter,
        keyspace = keyspace
      ),
      keyspace = keyspace,
      datacenter = datacenter,
      runMigrations = runMigrations,
      cleanup = cleanup,
      configureExposedConfiguration = configureExposedConfiguration
    )
  }
}

/**
 * Options for using an externally provided Cassandra instance.
 * This class holds the configuration for the external instance directly (non-nullable).
 */
@StoveDsl
class ProvidedCassandraSystemOptions(
  /**
   * The configuration for the provided Cassandra instance.
   */
  val config: CassandraExposedConfiguration,
  keyspace: String = "stove",
  datacenter: String = "datacenter1",
  /**
   * Whether to run migrations on the external instance.
   */
  val runMigrations: Boolean = true,
  cleanup: suspend (CqlSession) -> Unit = {},
  configureExposedConfiguration: (CassandraExposedConfiguration) -> List<String>
) : CassandraSystemOptions(
    keyspace = keyspace,
    datacenter = datacenter,
    container = CassandraContainerOptions(),
    cleanup = cleanup,
    configureExposedConfiguration = configureExposedConfiguration
  ),
  ProvidedSystemOptions<CassandraExposedConfiguration> {
  override val providedConfig: CassandraExposedConfiguration = config
  override val runMigrationsForProvided: Boolean = runMigrations
}
