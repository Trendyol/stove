package com.trendyol.stove.testing.e2e.rdbms.postgres

import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.containers.*
import com.trendyol.stove.testing.e2e.database.migrations.MigrationCollection
import com.trendyol.stove.testing.e2e.rdbms.*
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
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

@StoveDsl
data class PostgresqlOptions(
  val databaseName: String = "stove",
  val username: String = "sa",
  val password: String = "sa",
  val container: PostgresqlContainerOptions = PostgresqlContainerOptions(),
  override val configureExposedConfiguration: (
    RelationalDatabaseExposedConfiguration
  ) -> List<String>
) : SystemOptions,
  ConfiguresExposedConfiguration<RelationalDatabaseExposedConfiguration> {
  val migrationCollection: MigrationCollection<PostgresSqlMigrationContext> = MigrationCollection()

  @StoveDsl
  fun migrations(migration: MigrationCollection<PostgresSqlMigrationContext>.() -> Unit): PostgresqlOptions =
    migration(
      migrationCollection
    ).let {
      this
    }
}

internal class PostgresqlContext(
  container: StovePostgresqlContainer,
  val options: PostgresqlOptions
) : RelationalDatabaseContext<StovePostgresqlContainer>(container, options.configureExposedConfiguration)

@StoveDsl
data class PostgresSqlMigrationContext(
  val options: PostgresqlOptions,
  val operations: NativeSqlOperations,
  val executeAsRoot: suspend (String) -> Unit
)

internal fun TestSystem.withPostgresql(options: PostgresqlOptions): TestSystem =
  withProvidedRegistry(options.container.imageWithTag, options.container.registry, options.container.compatibleSubstitute) {
    options.container
      .useContainerFn(it)
      .withDatabaseName(options.databaseName)
      .withUsername(options.username)
      .withPassword(options.password)
      .withReuse(this.options.keepDependenciesRunning)
      .let { c -> c as StovePostgresqlContainer }
      .apply(options.container.containerFn)
  }.let { getOrRegister(PostgresqlSystem(this, PostgresqlContext(it, options))) }
    .let { this }

internal fun TestSystem.postgresql(): PostgresqlSystem =
  getOrNone<PostgresqlSystem>().getOrElse {
    throw SystemNotRegisteredException(PostgresqlSystem::class)
  }

@StoveDsl
fun WithDsl.postgresql(configure: () -> PostgresqlOptions): TestSystem =
  this.testSystem.withPostgresql(configure())

@StoveDsl
suspend fun ValidationDsl.postgresql(validation: @StoveDsl suspend PostgresqlSystem.() -> Unit): Unit =
  validation(this.testSystem.postgresql())
