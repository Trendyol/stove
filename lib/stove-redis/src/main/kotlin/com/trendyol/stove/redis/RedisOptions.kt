@file:Suppress("unused")

package com.trendyol.stove.redis

import arrow.core.getOrElse
import com.redis.testcontainers.RedisContainer
import com.trendyol.stove.containers.*
import com.trendyol.stove.database.migrations.*
import com.trendyol.stove.system.*
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import org.testcontainers.utility.DockerImageName

open class StoveRedisContainer(
  override val imageNameAccess: DockerImageName
) : RedisContainer(imageNameAccess),
  StoveContainer

data class RedisContainerOptions(
  override val registry: String = DEFAULT_REGISTRY,
  override val image: String = RedisContainer.DEFAULT_IMAGE_NAME.unversionedPart,
  override val tag: String = RedisContainer.DEFAULT_TAG,
  override val compatibleSubstitute: String? = null,
  override val useContainerFn: UseContainerFn<StoveRedisContainer> = { StoveRedisContainer(it) },
  override val containerFn: ContainerFn<StoveRedisContainer> = { }
) : ContainerOptions<StoveRedisContainer>

/**
 * Context provided to Redis migrations.
 * Contains the Redis connection and options for performing setup operations.
 *
 * @property connection The Redis connection for executing commands
 * @property options The Redis system options
 */
@StoveDsl
data class RedisMigrationContext(
  val connection: StatefulRedisConnection<String, String>,
  val options: RedisOptions
)

/**
 * Convenience type alias for Redis migrations.
 *
 * Instead of writing `DatabaseMigration<RedisMigrationContext>`, use `RedisMigration`:
 * ```kotlin
 * class MyMigration : RedisMigration {
 *   override val order: Int = 1
 *   override suspend fun execute(connection: RedisMigrationContext) { ... }
 * }
 * ```
 */
typealias RedisMigration = DatabaseMigration<RedisMigrationContext>

/**
 * Options for configuring the Redis system in container mode.
 */
@StoveDsl
open class RedisOptions(
  open val database: Int = 8,
  open val password: String = "password",
  open val container: RedisContainerOptions = RedisContainerOptions(),
  open val cleanup: suspend (RedisClient) -> Unit = {},
  override val configureExposedConfiguration: (RedisExposedConfiguration) -> List<String>
) : SystemOptions,
  ConfiguresExposedConfiguration<RedisExposedConfiguration>,
  SupportsMigrations<RedisMigrationContext, RedisOptions> {
  override val migrationCollection: MigrationCollection<RedisMigrationContext> = MigrationCollection()

  companion object {
    /**
     * Creates options configured to use an externally provided Redis instance
     * instead of a testcontainer.
     *
     * @param host The Redis host
     * @param port The Redis port
     * @param password The Redis password
     * @param database The Redis database number
     * @param runMigrations Whether to run migrations on the external instance (default: true)
     * @param cleanup A suspend function to clean up data after tests complete
     * @param configureExposedConfiguration Function to map exposed config to application properties
     */
    @StoveDsl
    fun provided(
      host: String,
      port: Int,
      password: String,
      database: Int = 8,
      runMigrations: Boolean = true,
      cleanup: suspend (RedisClient) -> Unit = {},
      configureExposedConfiguration: (RedisExposedConfiguration) -> List<String>
    ): ProvidedRedisOptions = ProvidedRedisOptions(
      config = RedisExposedConfiguration(
        host = host,
        port = port,
        redisUri = "redis://$host:$port",
        database = database.toString(),
        password = password
      ),
      database = database,
      password = password,
      runMigrations = runMigrations,
      cleanup = cleanup,
      configureExposedConfiguration = configureExposedConfiguration
    )
  }
}

/**
 * Options for using an externally provided Redis instance.
 * This class holds the configuration for the external instance directly (non-nullable).
 */
@StoveDsl
class ProvidedRedisOptions(
  /**
   * The configuration for the provided Redis instance.
   */
  val config: RedisExposedConfiguration,
  database: Int = 8,
  password: String = "password",
  /**
   * Whether to run migrations on the external instance.
   */
  val runMigrations: Boolean = true,
  cleanup: suspend (RedisClient) -> Unit = {},
  configureExposedConfiguration: (RedisExposedConfiguration) -> List<String>
) : RedisOptions(
    database = database,
    password = password,
    container = RedisContainerOptions(),
    cleanup = cleanup,
    configureExposedConfiguration = configureExposedConfiguration
  ),
  ProvidedSystemOptions<RedisExposedConfiguration> {
  override val providedConfig: RedisExposedConfiguration = config
  override val runMigrationsForProvided: Boolean = runMigrations
}

@StoveDsl
data class RedisExposedConfiguration(
  val host: String,
  val port: Int,
  val redisUri: String,
  val database: String,
  val password: String
) : ExposedConfiguration

@StoveDsl
data class RedisContext(
  val runtime: SystemRuntime,
  val options: RedisOptions
)

/**
 * Configures Redis system.
 *
 * For container-based setup:
 * ```kotlin
 * redis {
 *   RedisOptions(
 *     database = 8,
 *     password = "password",
 *     cleanup = { client -> client.connect().sync().flushall() },
 *     configureExposedConfiguration = { cfg -> listOf(...) }
 *   )
 * }
 * ```
 *
 * For provided (external) instance:
 * ```kotlin
 * redis {
 *   RedisOptions.provided(
 *     host = "localhost",
 *     port = 6379,
 *     password = "password",
 *     database = 8,
 *     cleanup = { client -> client.connect().sync().flushall() },
 *     configureExposedConfiguration = { cfg -> listOf(...) }
 *   )
 * }
 * ```
 */
@StoveDsl
fun WithDsl.redis(
  configure: () -> RedisOptions
): Stove {
  val options = configure()

  val runtime: SystemRuntime = if (options is ProvidedRedisOptions) {
    ProvidedRuntime
  } else {
    withProvidedRegistry(
      options.container.image,
      options.container.registry,
      options.container.compatibleSubstitute
    ) { dockerImageName ->
      options.container
        .useContainerFn(dockerImageName)
        .withCommand("redis-server", "--requirepass", options.password)
        .withReuse(stove.options.keepDependenciesRunning)
        .let { c -> c as StoveRedisContainer }
        .apply(options.container.containerFn)
    }
  }
  return stove.withRedis(options, runtime)
}

@StoveDsl
suspend fun ValidationDsl.redis(validation: suspend RedisSystem.() -> Unit): Unit = validation(this.stove.redis())

internal fun Stove.redis(): RedisSystem =
  getOrNone<RedisSystem>().getOrElse {
    throw SystemNotRegisteredException(RedisSystem::class)
  }

internal fun Stove.withRedis(
  options: RedisOptions,
  runtime: SystemRuntime
): Stove {
  getOrRegister(RedisSystem(this, RedisContext(runtime, options)))
  return this
}
