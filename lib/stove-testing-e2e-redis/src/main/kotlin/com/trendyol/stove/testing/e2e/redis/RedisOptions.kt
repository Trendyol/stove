package com.trendyol.stove.testing.e2e.redis

import arrow.core.getOrElse
import com.redis.testcontainers.RedisContainer
import com.trendyol.stove.testing.e2e.containers.*
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import org.testcontainers.utility.DockerImageName

open class StoveRedisContainer(
  override val imageNameAccess: DockerImageName
) : RedisContainer(imageNameAccess), StoveContainer

data class RedisContainerOptions(
  override val registry: String = DEFAULT_REGISTRY,
  override val image: String = RedisContainer.DEFAULT_IMAGE_NAME.unversionedPart,
  override val tag: String = RedisContainer.DEFAULT_TAG,
  override val compatibleSubstitute: String? = null,
  override val useContainerFn: UseContainerFn<StoveRedisContainer> = { StoveRedisContainer(it) },
  override val containerFn: ContainerFn<StoveRedisContainer> = { }
) : ContainerOptions<StoveRedisContainer>

@StoveDsl
data class RedisOptions(
  val database: Int = 8,
  val password: String = "password",
  val container: RedisContainerOptions = RedisContainerOptions(),
  override val configureExposedConfiguration: (RedisExposedConfiguration) -> List<String> = { _ -> listOf() }
) : SystemOptions, ConfiguresExposedConfiguration<RedisExposedConfiguration>

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
  val container: RedisContainer,
  val options: RedisOptions
)

@StoveDsl
fun WithDsl.redis(
  configure: () -> RedisOptions = { RedisOptions() }
): TestSystem = this.testSystem.withRedis(configure())

@StoveDsl
suspend fun ValidationDsl.redis(validation: suspend RedisSystem.() -> Unit): Unit = validation(this.testSystem.redis())

internal fun TestSystem.redis(): RedisSystem =
  getOrNone<RedisSystem>().getOrElse {
    throw SystemNotRegisteredException(RedisSystem::class)
  }

internal fun TestSystem.withRedis(options: RedisOptions = RedisOptions()): TestSystem =
  withProvidedRegistry(options.container.image, options.container.registry, options.container.compatibleSubstitute) {
    options.container.useContainerFn(it)
      .withCommand("redis-server", "--requirepass", options.password)
      .withReuse(this.options.keepDependenciesRunning)
      .let { c -> c as StoveRedisContainer }
      .apply(options.container.containerFn)
  }.let { getOrRegister(RedisSystem(this, RedisContext(it, options))) }
    .let { this }
