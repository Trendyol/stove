package com.trendyol.stove.testing.e2e.couchbase

import arrow.core.getOrElse
import com.couchbase.client.kotlin.Cluster
import com.couchbase.client.kotlin.codec.*
import com.trendyol.stove.testing.e2e.containers.*
import com.trendyol.stove.testing.e2e.database.migrations.*
import com.trendyol.stove.testing.e2e.serialization.E2eObjectMapperConfig
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import org.testcontainers.couchbase.BucketDefinition

data class CouchbaseExposedConfiguration(
  val connectionString: String,
  val hostsWithPort: String,
  val username: String,
  val password: String
) : ExposedConfiguration

@StoveDsl
data class CouchbaseSystemOptions(
  val defaultBucket: String,
  val containerOptions: CouchbaseContainerOptions = CouchbaseContainerOptions(),
  val clusterSerDe: JsonSerializer = JacksonJsonSerializer(E2eObjectMapperConfig.createObjectMapperWithDefaults()),
  val clusterTranscoder: Transcoder = JsonTranscoder(clusterSerDe),
  override val configureExposedConfiguration: (CouchbaseExposedConfiguration) -> List<String>
) : SystemOptions, ConfiguresExposedConfiguration<CouchbaseExposedConfiguration> {
  internal val migrationCollection: MigrationCollection<Cluster> = MigrationCollection()

  /**
   * Helps for registering migrations before the tests run.
   * @see MigrationCollection
   * @see DatabaseMigration
   */
  @StoveDsl
  fun migrations(
    migration: MigrationCollection<Cluster>.() -> Unit
  ): CouchbaseSystemOptions = migration(migrationCollection).let { this }
}

@StoveDsl
data class CouchbaseContext(
  val bucket: BucketDefinition,
  val container: StoveCouchbaseContainer,
  val options: CouchbaseSystemOptions
)

@StoveDsl
data class CouchbaseContainerOptions(
  override val registry: String = DEFAULT_REGISTRY,
  override val image: String = "couchbase/server",
  override val tag: String = "latest",
  override val compatibleSubstitute: String? = null,
  override val useContainerFn: UseContainerFn<StoveCouchbaseContainer> = { StoveCouchbaseContainer(it) },
  override val containerFn: ContainerFn<StoveCouchbaseContainer> = { }
) : ContainerOptions<StoveCouchbaseContainer>

internal fun TestSystem.withCouchbase(options: CouchbaseSystemOptions): TestSystem {
  val bucketDefinition = BucketDefinition(options.defaultBucket)
  val couchbaseContainer = withProvidedRegistry(
    imageName = options.containerOptions.imageWithTag,
    registry = options.containerOptions.registry,
    compatibleSubstitute = options.containerOptions.compatibleSubstitute
  ) {
    options.containerOptions.useContainerFn(it)
      .withBucket(bucketDefinition)
      .withReuse(this.options.keepDependenciesRunning)
      .let { c -> c as StoveCouchbaseContainer }
      .apply(options.containerOptions.containerFn)
  }
  this.getOrRegister(
    CouchbaseSystem(this, CouchbaseContext(bucketDefinition, couchbaseContainer, options))
  )
  return this
}

internal fun TestSystem.couchbase(): CouchbaseSystem =
  getOrNone<CouchbaseSystem>().getOrElse {
    throw SystemNotRegisteredException(CouchbaseSystem::class)
  }

@StoveDsl
fun WithDsl.couchbase(
  configure: @StoveDsl () -> CouchbaseSystemOptions
): TestSystem = this.testSystem.withCouchbase(configure())

@StoveDsl
suspend fun ValidationDsl.couchbase(
  validation: @CouchbaseDsl suspend CouchbaseSystem.() -> Unit
): Unit = validation(this.testSystem.couchbase())
