package com.trendyol.stove.testing.e2e.rdbms.mssql

import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.containers.*
import com.trendyol.stove.testing.e2e.database.migrations.*
import com.trendyol.stove.testing.e2e.rdbms.RelationalDatabaseExposedConfiguration
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import org.testcontainers.containers.MSSQLServerContainer
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
) : MSSQLServerContainer<StoveMsSqlContainer>(imageNameAccess),
  StoveContainer

data class MssqlContainerOptions(
  override val registry: String = DEFAULT_REGISTRY,
  override val image: String = MSSQLServerContainer.IMAGE,
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

data class MsSqlOptions(
  val applicationName: String,
  val databaseName: String,
  val userName: String,
  val password: String,
  val container: MssqlContainerOptions = MssqlContainerOptions(),
  override val configureExposedConfiguration: (
    RelationalDatabaseExposedConfiguration
  ) -> List<String>
) : SystemOptions,
  ConfiguresExposedConfiguration<RelationalDatabaseExposedConfiguration> {
  val migrationCollection: MigrationCollection<SqlMigrationContext> = MigrationCollection()

  /**
   * Helps for registering migrations before the tests run.
   * @see MigrationCollection
   * @see DatabaseMigration
   */
  @StoveDsl
  fun migrations(migration: MigrationCollection<SqlMigrationContext>.() -> Unit): MsSqlOptions =
    migration(
      migrationCollection
    ).let {
      this
    }
}

@StoveDsl
fun WithDsl.mssql(
  configure: () -> MsSqlOptions
): TestSystem = this.testSystem.withMsSql(configure())

@StoveDsl
suspend fun ValidationDsl.mssql(
  validation: @StoveDsl suspend MsSqlSystem.() -> Unit
): Unit = validation(this.testSystem.mssql())

internal fun TestSystem.mssql(): MsSqlSystem =
  getOrNone<MsSqlSystem>().getOrElse {
    throw SystemNotRegisteredException(MsSqlSystem::class)
  }

internal fun TestSystem.withMsSql(options: MsSqlOptions): TestSystem =
  withProvidedRegistry(
    options.container.imageWithTag,
    options.container.registry,
    options.container.compatibleSubstitute
  ) {
    options.container
      .useContainerFn(it)
      .acceptLicense()
      .withEnv("MSSQL_USER", options.userName)
      .withEnv("MSSQL_SA_PASSWORD", options.password)
      .withEnv("MSSQL_DB", options.databaseName)
      .withPassword(options.password)
      .withReuse(this.options.keepDependenciesRunning)
      .apply(options.container.containerFn)
  }.let { getOrRegister(MsSqlSystem(this, MsSqlContext(it, options))) }
    .let { this }
