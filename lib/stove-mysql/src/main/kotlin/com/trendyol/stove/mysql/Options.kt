@file:Suppress("unused")

package com.trendyol.stove.mysql

import arrow.core.getOrElse
import com.trendyol.stove.containers.*
import com.trendyol.stove.database.migrations.*
import com.trendyol.stove.rdbms.*
import com.trendyol.stove.system.*
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName

const val DEFAULT_MYSQL_IMAGE_NAME = "mysql"

open class StoveMySqlContainer(
  override val imageNameAccess: DockerImageName
) : MySQLContainer(imageNameAccess),
  StoveContainer

/**
 * Container options for MySQL.
 */
data class MySqlContainerOptions(
  override val registry: String = DEFAULT_REGISTRY,
  override val image: String = DEFAULT_MYSQL_IMAGE_NAME,
  override val tag: String = "8.4",
  override val compatibleSubstitute: String? = null,
  override val useContainerFn: UseContainerFn<StoveMySqlContainer> = { StoveMySqlContainer(it) },
  override val containerFn: ContainerFn<StoveMySqlContainer> = { }
) : ContainerOptions<StoveMySqlContainer>

/**
 * Options for configuring the MySQL system in container mode.
 */
@StoveDsl
open class MySqlOptions(
  open val databaseName: String = "stove",
  open val username: String = "sa",
  open val password: String = "sa",
  open val container: MySqlContainerOptions = MySqlContainerOptions(),
  open val cleanup: suspend (NativeSqlOperations) -> Unit = {},
  override val configureExposedConfiguration: (RelationalDatabaseExposedConfiguration) -> List<String>
) : SystemOptions,
  ConfiguresExposedConfiguration<RelationalDatabaseExposedConfiguration>,
  SupportsMigrations<MySqlMigrationContext, MySqlOptions> {
  override val migrationCollection: MigrationCollection<MySqlMigrationContext> = MigrationCollection()

  companion object {
    /**
     * Creates options configured to use an externally provided MySQL instance
     * instead of a testcontainer.
     *
     * @param jdbcUrl The JDBC URL for the MySQL instance
     * @param host The host of the MySQL instance
     * @param port The port of the MySQL instance
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
    ): ProvidedMySqlOptions = ProvidedMySqlOptions(
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
 * Options for using an externally provided MySQL instance.
 * This class holds the configuration for the external instance directly (non-nullable).
 */
@StoveDsl
class ProvidedMySqlOptions(
  /**
   * The configuration for the provided MySQL instance.
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
) : MySqlOptions(
    databaseName = databaseName,
    username = username,
    password = password,
    container = MySqlContainerOptions(),
    cleanup = cleanup,
    configureExposedConfiguration = configureExposedConfiguration
  ),
  ProvidedSystemOptions<RelationalDatabaseExposedConfiguration> {
  override val providedConfig: RelationalDatabaseExposedConfiguration = config
  override val runMigrationsForProvided: Boolean = runMigrations
}

@StoveDsl
data class MySqlMigrationContext(
  val options: MySqlOptions,
  val operations: NativeSqlOperations,
  val executeAsRoot: suspend (String) -> Unit
)

/**
 * Convenience type alias for MySQL migrations.
 *
 * Instead of writing `DatabaseMigration<MySqlMigrationContext>`, use `MySqlMigration`:
 * ```kotlin
 * class MyMigration : MySqlMigration {
 *   override val order: Int = 1
 *   override suspend fun execute(connection: MySqlMigrationContext) { ... }
 * }
 * ```
 */
typealias MySqlMigration = DatabaseMigration<MySqlMigrationContext>

internal class MySqlContext(
  val runtime: SystemRuntime,
  val options: MySqlOptions
)

internal fun Stove.withMySql(
  options: MySqlOptions,
  runtime: SystemRuntime
): Stove {
  getOrRegister(MySqlSystem(this, MySqlContext(runtime, options)))
  return this
}

internal fun Stove.mysql(): MySqlSystem =
  getOrNone<MySqlSystem>().getOrElse {
    throw SystemNotRegisteredException(MySqlSystem::class)
  }

/**
 * Configures MySQL system.
 *
 * For container-based setup:
 * ```kotlin
 * mysql {
 *   MySqlOptions(
 *     databaseName = "mydb",
 *     cleanup = { ops -> ops.execute("TRUNCATE TABLE ...") },
 *     configureExposedConfiguration = { cfg -> listOf(...) }
 *   )
 * }
 * ```
 *
 * For provided (external) instance:
 * ```kotlin
 * mysql {
 *   MySqlOptions.provided(
 *     jdbcUrl = "jdbc:mysql://localhost:3306/mydb",
 *     host = "localhost",
 *     port = 3306,
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
fun WithDsl.mysql(
  configure: () -> MySqlOptions
): Stove {
  val options = configure()

  val runtime: SystemRuntime = if (options is ProvidedMySqlOptions) {
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
        .let { c -> c as StoveMySqlContainer }
        .apply(options.container.containerFn)
    }
  }
  return stove.withMySql(options, runtime)
}

@StoveDsl
suspend fun ValidationDsl.mysql(validation: @StoveDsl suspend MySqlSystem.() -> Unit): Unit =
  validation(this.stove.mysql())
