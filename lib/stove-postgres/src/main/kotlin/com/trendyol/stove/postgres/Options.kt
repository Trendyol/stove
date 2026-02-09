@file:Suppress("unused")

package com.trendyol.stove.postgres

import arrow.core.getOrElse
import com.trendyol.stove.containers.*
import com.trendyol.stove.database.migrations.*
import com.trendyol.stove.rdbms.*
import com.trendyol.stove.system.*
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

const val DEFAULT_POSTGRES_IMAGE_NAME = "postgres"

open class StovePostgresqlContainer(
  override val imageNameAccess: DockerImageName
) : PostgreSQLContainer(imageNameAccess),
  StoveContainer

data class PostgresqlContainerOptions(
  override val registry: String = DEFAULT_REGISTRY,
  override val image: String = DEFAULT_POSTGRES_IMAGE_NAME,
  override val tag: String = "latest",
  override val compatibleSubstitute: String? = null,
  override val useContainerFn: UseContainerFn<StovePostgresqlContainer> = { StovePostgresqlContainer(it) },
  override val containerFn: ContainerFn<StovePostgresqlContainer> = { }
) : ContainerOptions<StovePostgresqlContainer>

/**
 * Options for configuring the PostgreSQL system in container mode.
 */
@StoveDsl
open class PostgresqlOptions(
  open val databaseName: String = "stove",
  open val username: String = "sa",
  open val password: String = "sa",
  open val container: PostgresqlContainerOptions = PostgresqlContainerOptions(),
  open val cleanup: suspend (NativeSqlOperations) -> Unit = {},
  override val configureExposedConfiguration: (RelationalDatabaseExposedConfiguration) -> List<String>
) : SystemOptions,
  ConfiguresExposedConfiguration<RelationalDatabaseExposedConfiguration>,
  SupportsMigrations<PostgresSqlMigrationContext, PostgresqlOptions> {
  override val migrationCollection: MigrationCollection<PostgresSqlMigrationContext> = MigrationCollection()

  companion object {
    /**
     * Creates options configured to use an externally provided PostgreSQL instance
     * instead of a testcontainer.
     *
     * @param jdbcUrl The JDBC URL for the PostgreSQL instance
     * @param host The host of the PostgreSQL instance
     * @param port The port of the PostgreSQL instance
     * @param databaseName The database name
     * @param username The username for authentication
     * @param password The password for authentication
     * @param runMigrations Whether to run migrations on the external instance (default: true)
     * @param cleanup A suspend function to clean up data after tests complete
     * @param configureExposedConfiguration Function to map exposed config to application properties
     */
    @StoveDsl
    fun provided(
      jdbcUrl: String,
      host: String,
      port: Int,
      databaseName: String = "stove",
      username: String = "sa",
      password: String = "sa",
      runMigrations: Boolean = true,
      cleanup: suspend (NativeSqlOperations) -> Unit = {},
      configureExposedConfiguration: (RelationalDatabaseExposedConfiguration) -> List<String>
    ): ProvidedPostgresqlOptions = ProvidedPostgresqlOptions(
      config = RelationalDatabaseExposedConfiguration(
        jdbcUrl = jdbcUrl,
        host = host,
        port = port,
        username = username,
        password = password
      ),
      databaseName = databaseName,
      username = username,
      password = password,
      runMigrations = runMigrations,
      cleanup = cleanup,
      configureExposedConfiguration = configureExposedConfiguration
    )
  }
}

/**
 * Options for using an externally provided PostgreSQL instance.
 * This class holds the configuration for the external instance directly (non-nullable).
 */
@StoveDsl
class ProvidedPostgresqlOptions(
  /**
   * The configuration for the provided PostgreSQL instance.
   */
  val config: RelationalDatabaseExposedConfiguration,
  databaseName: String = "stove",
  username: String = "sa",
  password: String = "sa",
  cleanup: suspend (NativeSqlOperations) -> Unit = {},
  /**
   * Whether to run migrations on the external instance.
   */
  val runMigrations: Boolean = true,
  configureExposedConfiguration: (RelationalDatabaseExposedConfiguration) -> List<String>
) : PostgresqlOptions(
    databaseName = databaseName,
    username = username,
    password = password,
    container = PostgresqlContainerOptions(),
    cleanup = cleanup,
    configureExposedConfiguration = configureExposedConfiguration
  ),
  ProvidedSystemOptions<RelationalDatabaseExposedConfiguration> {
  override val providedConfig: RelationalDatabaseExposedConfiguration = config
  override val runMigrationsForProvided: Boolean = runMigrations
}

@StoveDsl
data class PostgresSqlMigrationContext(
  val options: PostgresqlOptions,
  val operations: NativeSqlOperations,
  val executeAsRoot: suspend (String) -> Unit
)

/**
 * Convenience type alias for PostgreSQL migrations.
 *
 * Instead of writing `DatabaseMigration<PostgresSqlMigrationContext>`, use `PostgresqlMigration`:
 * ```kotlin
 * class MyMigration : PostgresqlMigration {
 *   override val order: Int = 1
 *   override suspend fun execute(connection: PostgresSqlMigrationContext) { ... }
 * }
 * ```
 */
typealias PostgresqlMigration = DatabaseMigration<PostgresSqlMigrationContext>

internal class PostgresqlContext(
  val runtime: SystemRuntime,
  val options: PostgresqlOptions
)

internal fun Stove.withPostgresql(
  options: PostgresqlOptions,
  runtime: SystemRuntime
): Stove {
  getOrRegister(PostgresqlSystem(this, PostgresqlContext(runtime, options)))
  return this
}

internal fun Stove.postgresql(): PostgresqlSystem =
  getOrNone<PostgresqlSystem>().getOrElse {
    throw SystemNotRegisteredException(PostgresqlSystem::class)
  }

/**
 * Configures PostgreSQL system.
 *
 * For container-based setup:
 * ```kotlin
 * postgresql {
 *   PostgresqlOptions(
 *     databaseName = "mydb",
 *     cleanup = { ops -> ops.execute("TRUNCATE TABLE ...") },
 *     configureExposedConfiguration = { cfg -> listOf(...) }
 *   )
 * }
 * ```
 *
 * For provided (external) instance:
 * ```kotlin
 * postgresql {
 *   PostgresqlOptions.provided(
 *     jdbcUrl = "jdbc:postgresql://localhost:5432/mydb",
 *     host = "localhost",
 *     port = 5432,
 *     username = "user",
 *     password = "pass",
 *     runMigrations = true,
 *     cleanup = { ops -> ops.execute("TRUNCATE TABLE ...") },
 *     configureExposedConfiguration = { cfg -> listOf(...) }
 *   )
 * }
 * ```
 */
@StoveDsl
fun WithDsl.postgresql(
  configure: () -> PostgresqlOptions
): Stove {
  val options = configure()

  val runtime: SystemRuntime = if (options is ProvidedPostgresqlOptions) {
    ProvidedRuntime
  } else {
    withProvidedRegistry(
      options.container.imageWithTag,
      options.container.registry,
      options.container.compatibleSubstitute
    ) { dockerImageName ->
      options.container
        .useContainerFn(dockerImageName)
        .withDatabaseName(options.databaseName)
        .withUsername(options.username)
        .withPassword(options.password)
        .withReuse(stove.options.keepDependenciesRunning)
        .let { c -> c as StovePostgresqlContainer }
        .apply(options.container.containerFn)
    }
  }
  return stove.withPostgresql(options, runtime)
}

@StoveDsl
suspend fun ValidationDsl.postgresql(validation: @StoveDsl suspend PostgresqlSystem.() -> Unit): Unit =
  validation(this.stove.postgresql())
