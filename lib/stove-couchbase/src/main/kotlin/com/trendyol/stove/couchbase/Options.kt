package com.trendyol.stove.couchbase

import arrow.core.getOrElse
import com.couchbase.client.kotlin.Cluster
import com.couchbase.client.kotlin.codec.*
import com.trendyol.stove.containers.*
import com.trendyol.stove.database.migrations.*
import com.trendyol.stove.serialization.E2eObjectMapperConfig
import com.trendyol.stove.system.*
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl
import org.testcontainers.couchbase.BucketDefinition

data class CouchbaseExposedConfiguration(
  val connectionString: String,
  val hostsWithPort: String,
  val username: String,
  val password: String
) : ExposedConfiguration

/**
 * Options for configuring the Couchbase system in container mode.
 */
@StoveDsl
open class CouchbaseSystemOptions(
  open val defaultBucket: String,
  open val containerOptions: CouchbaseContainerOptions = CouchbaseContainerOptions(),
  open val clusterSerDe: JsonSerializer = JacksonJsonSerializer(E2eObjectMapperConfig.createObjectMapperWithDefaults()),
  open val clusterTranscoder: Transcoder = JsonTranscoder(clusterSerDe),
  open val cleanup: suspend (Cluster) -> Unit = {},
  override val configureExposedConfiguration: (CouchbaseExposedConfiguration) -> List<String>
) : SystemOptions,
  ConfiguresExposedConfiguration<CouchbaseExposedConfiguration>,
  SupportsMigrations<Cluster, CouchbaseSystemOptions> {
  override val migrationCollection: MigrationCollection<Cluster> = MigrationCollection()

  companion object {
    /**
     * Creates options configured to use an externally provided Couchbase instance
     * instead of a testcontainer.
     *
     * @param connectionString The Couchbase connection string (e.g., "couchbase://localhost:8091")
     * @param username The username for authentication
     * @param password The password for authentication
     * @param defaultBucket The default bucket name
     * @param runMigrations Whether to run migrations on the external instance (default: true)
     * @param cleanup A suspend function to clean up data after tests complete
     * @param configureExposedConfiguration Function to map exposed config to application properties
     */
    @StoveDsl
    fun provided(
      connectionString: String,
      username: String,
      password: String,
      defaultBucket: String,
      runMigrations: Boolean = true,
      cleanup: suspend (Cluster) -> Unit = {},
      configureExposedConfiguration: (CouchbaseExposedConfiguration) -> List<String>
    ): ProvidedCouchbaseSystemOptions {
      val hostsWithPort = connectionString.replace("couchbase://", "")
      return ProvidedCouchbaseSystemOptions(
        config = CouchbaseExposedConfiguration(
          connectionString = connectionString,
          hostsWithPort = hostsWithPort,
          username = username,
          password = password
        ),
        defaultBucket = defaultBucket,
        runMigrations = runMigrations,
        cleanup = cleanup,
        configureExposedConfiguration = configureExposedConfiguration
      )
    }
  }
}

/**
 * Options for using an externally provided Couchbase instance.
 * This class holds the configuration for the external instance directly (non-nullable).
 */
@StoveDsl
class ProvidedCouchbaseSystemOptions(
  /**
   * The configuration for the provided Couchbase instance.
   */
  val config: CouchbaseExposedConfiguration,
  defaultBucket: String,
  clusterSerDe: JsonSerializer = JacksonJsonSerializer(E2eObjectMapperConfig.createObjectMapperWithDefaults()),
  clusterTranscoder: Transcoder = JsonTranscoder(clusterSerDe),
  cleanup: suspend (Cluster) -> Unit = {},
  /**
   * Whether to run migrations on the external instance.
   */
  val runMigrations: Boolean = true,
  configureExposedConfiguration: (CouchbaseExposedConfiguration) -> List<String>
) : CouchbaseSystemOptions(
    defaultBucket = defaultBucket,
    containerOptions = CouchbaseContainerOptions(),
    clusterSerDe = clusterSerDe,
    clusterTranscoder = clusterTranscoder,
    cleanup = cleanup,
    configureExposedConfiguration = configureExposedConfiguration
  ),
  ProvidedSystemOptions<CouchbaseExposedConfiguration> {
  override val providedConfig: CouchbaseExposedConfiguration = config
  override val runMigrationsForProvided: Boolean = runMigrations
}

@StoveDsl
data class CouchbaseContext(
  val bucket: BucketDefinition,
  val runtime: SystemRuntime,
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

internal fun Stove.withCouchbase(
  options: CouchbaseSystemOptions,
  runtime: SystemRuntime
): Stove {
  val bucketDefinition = BucketDefinition(options.defaultBucket)
  this.getOrRegister(
    CouchbaseSystem(this, CouchbaseContext(bucketDefinition, runtime, options))
  )
  return this
}

internal fun Stove.couchbase(): CouchbaseSystem =
  getOrNone<CouchbaseSystem>().getOrElse {
    throw SystemNotRegisteredException(CouchbaseSystem::class)
  }

/**
 * Configures Couchbase system.
 *
 * For container-based setup:
 * ```kotlin
 * couchbase {
 *   CouchbaseSystemOptions(
 *     defaultBucket = "myBucket",
 *     cleanup = { cluster -> cluster.query("DELETE FROM ...") },
 *     configureExposedConfiguration = { cfg -> listOf(...) }
 *   )
 * }
 * ```
 *
 * For provided (external) instance:
 * ```kotlin
 * couchbase {
 *   CouchbaseSystemOptions.provided(
 *     connectionString = "couchbase://localhost:8091",
 *     username = "admin",
 *     password = "password",
 *     defaultBucket = "myBucket",
 *     runMigrations = true,
 *     cleanup = { cluster -> cluster.query("DELETE FROM ...") },
 *     configureExposedConfiguration = { cfg -> listOf(...) }
 *   )
 * }
 * ```
 */
@StoveDsl
fun WithDsl.couchbase(
  configure: @StoveDsl () -> CouchbaseSystemOptions
): Stove {
  val options = configure()
  val bucketDefinition = BucketDefinition(options.defaultBucket)

  val runtime: SystemRuntime = if (options is ProvidedCouchbaseSystemOptions) {
    ProvidedRuntime
  } else {
    withProvidedRegistry(
      imageName = options.containerOptions.imageWithTag,
      registry = options.containerOptions.registry,
      compatibleSubstitute = options.containerOptions.compatibleSubstitute
    ) { dockerImageName ->
      options.containerOptions
        .useContainerFn(dockerImageName)
        .withBucket(bucketDefinition)
        .withReuse(stove.options.keepDependenciesRunning)
        .let { c -> c as StoveCouchbaseContainer }
        .apply(options.containerOptions.containerFn)
    }
  }

  return stove.withCouchbase(options, runtime)
}

@StoveDsl
suspend fun ValidationDsl.couchbase(
  validation: @CouchbaseDsl suspend CouchbaseSystem.() -> Unit
): Unit = validation(this.stove.couchbase())
