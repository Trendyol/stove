@file:Suppress("unused")

package com.trendyol.stove.testing.e2e.rdbms.mssql

import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.containers.*
import com.trendyol.stove.testing.e2e.database.migrations.*
import com.trendyol.stove.testing.e2e.rdbms.*
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import org.testcontainers.utility.DockerImageName

sealed class ToolsPath(
  open val path: String
) {
  data object Before2019 : ToolsPath("mssql-tools")

  data object After2019 : ToolsPath("mssql-tools18")

  data class Custom(
    override val path: String
  ) : ToolsPath(path)

  override fun toString(): String = path
}

open class StoveMsSqlContainer(
  override val imageNameAccess: DockerImageName
) : org.testcontainers.mssqlserver.MSSQLServerContainer(imageNameAccess),
  StoveContainer

data class MssqlContainerOptions(
  override val registry: String = DEFAULT_REGISTRY,
  override val image: String = org.testcontainers.mssqlserver.MSSQLServerContainer.IMAGE,
  override val tag: String = "2022-latest",
  override val compatibleSubstitute: String? = null,
  /**
   * There is a breaking change introduced in the mssql-tools path after 2019.
   * Depending on your tag, you may need to set this value.
   */
  val toolsPath: ToolsPath = ToolsPath.After2019,
  override val useContainerFn: UseContainerFn<StoveMsSqlContainer> = { StoveMsSqlContainer(it) },
  override val containerFn: ContainerFn<StoveMsSqlContainer> = { }
) : ContainerOptions<StoveMsSqlContainer>

/**
 * Options for configuring the MSSQL system in container mode.
 */
@StoveDsl
open class MsSqlOptions(
  open val applicationName: String,
  open val databaseName: String,
  open val userName: String,
  open val password: String,
  open val container: MssqlContainerOptions = MssqlContainerOptions(),
  open val cleanup: suspend (NativeSqlOperations) -> Unit = {},
  override val configureExposedConfiguration: (RelationalDatabaseExposedConfiguration) -> List<String>
) : SystemOptions,
  ConfiguresExposedConfiguration<RelationalDatabaseExposedConfiguration>,
  SupportsMigrations<SqlMigrationContext, MsSqlOptions> {
  override val migrationCollection: MigrationCollection<SqlMigrationContext> = MigrationCollection()

  companion object {
    /**
     * Creates options configured to use an externally provided MSSQL instance
     * instead of a testcontainer.
     *
     * @param jdbcUrl The JDBC URL for the MSSQL instance
     * @param host The host of the MSSQL instance
     * @param port The port of the MSSQL instance
     * @param applicationName The application name
     * @param databaseName The database name
     * @param userName The username for authentication
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
      applicationName: String,
      databaseName: String,
      userName: String,
      password: String,
      runMigrations: Boolean = true,
      cleanup: suspend (NativeSqlOperations) -> Unit = {},
      configureExposedConfiguration: (RelationalDatabaseExposedConfiguration) -> List<String>
    ): ProvidedMsSqlOptions = ProvidedMsSqlOptions(
      config = RelationalDatabaseExposedConfiguration(
        jdbcUrl = jdbcUrl,
        host = host,
        port = port,
        username = userName,
        password = password
      ),
      applicationName = applicationName,
      databaseName = databaseName,
      userName = userName,
      password = password,
      runMigrations = runMigrations,
      cleanup = cleanup,
      configureExposedConfiguration = configureExposedConfiguration
    )
  }
}

/**
 * Options for using an externally provided MSSQL instance.
 * This class holds the configuration for the external instance directly (non-nullable).
 */
@StoveDsl
class ProvidedMsSqlOptions(
  /**
   * The configuration for the provided MSSQL instance.
   */
  val config: RelationalDatabaseExposedConfiguration,
  applicationName: String,
  databaseName: String,
  userName: String,
  password: String,
  cleanup: suspend (NativeSqlOperations) -> Unit = {},
  /**
   * Whether to run migrations on the external instance.
   */
  val runMigrations: Boolean = true,
  configureExposedConfiguration: (RelationalDatabaseExposedConfiguration) -> List<String>
) : MsSqlOptions(
    applicationName = applicationName,
    databaseName = databaseName,
    userName = userName,
    password = password,
    container = MssqlContainerOptions(),
    cleanup = cleanup,
    configureExposedConfiguration = configureExposedConfiguration
  ),
  ProvidedSystemOptions<RelationalDatabaseExposedConfiguration> {
  override val providedConfig: RelationalDatabaseExposedConfiguration = config
  override val runMigrationsForProvided: Boolean = runMigrations
}

@StoveDsl
data class SqlMigrationContext(
  val options: MsSqlOptions,
  val operations: NativeSqlOperations,
  val executeAsRoot: suspend (String) -> Unit
)

@StoveDsl
data class MsSqlContext(
  val runtime: SystemRuntime,
  val options: MsSqlOptions
)

internal fun TestSystem.withMsSql(
  options: MsSqlOptions,
  runtime: SystemRuntime
): TestSystem {
  getOrRegister(MsSqlSystem(this, MsSqlContext(runtime, options)))
  return this
}

internal fun TestSystem.mssql(): MsSqlSystem =
  getOrNone<MsSqlSystem>().getOrElse {
    throw SystemNotRegisteredException(MsSqlSystem::class)
  }

/**
 * Configures MSSQL system.
 *
 * For container-based setup:
 * ```kotlin
 * mssql {
 *   MsSqlOptions(
 *     applicationName = "myapp",
 *     databaseName = "mydb",
 *     userName = "sa",
 *     password = "password",
 *     cleanup = { ops -> ops.execute("TRUNCATE TABLE ...") },
 *     configureExposedConfiguration = { cfg -> listOf(...) }
 *   )
 * }
 * ```
 *
 * For provided (external) instance:
 * ```kotlin
 * mssql {
 *   MsSqlOptions.provided(
 *     jdbcUrl = "jdbc:sqlserver://localhost:1433;databaseName=mydb",
 *     host = "localhost",
 *     port = 1433,
 *     applicationName = "myapp",
 *     databaseName = "mydb",
 *     userName = "sa",
 *     password = "password",
 *     runMigrations = true,
 *     cleanup = { ops -> ops.execute("TRUNCATE TABLE ...") },
 *     configureExposedConfiguration = { cfg -> listOf(...) }
 *   )
 * }
 * ```
 */
@StoveDsl
fun WithDsl.mssql(
  configure: () -> MsSqlOptions
): TestSystem {
  val options = configure()

  val runtime: SystemRuntime = if (options is ProvidedMsSqlOptions) {
    ProvidedRuntime
  } else {
    withProvidedRegistry(
      options.container.imageWithTag,
      options.container.registry,
      options.container.compatibleSubstitute
    ) { dockerImageName ->
      options.container
        .useContainerFn(dockerImageName)
        .acceptLicense()
        .withEnv("MSSQL_USER", options.userName)
        .withEnv("MSSQL_SA_PASSWORD", options.password)
        .withEnv("MSSQL_DB", options.databaseName)
        .withPassword(options.password)
        .withReuse(testSystem.options.keepDependenciesRunning)
        .let { c -> c as StoveMsSqlContainer }
        .apply(options.container.containerFn)
    }
  }
  return testSystem.withMsSql(options, runtime)
}

@StoveDsl
suspend fun ValidationDsl.mssql(
  validation: @StoveDsl suspend MsSqlSystem.() -> Unit
): Unit = validation(this.testSystem.mssql())
