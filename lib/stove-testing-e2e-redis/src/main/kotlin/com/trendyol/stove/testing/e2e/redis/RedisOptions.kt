package com.trendyol.stove.testing.e2e.redis

import arrow.core.getOrElse
import com.redis.testcontainers.RedisContainer
import com.trendyol.stove.testing.e2e.containers.*
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl

@StoveDsl
data class RedisOptions(
    val database: Int = 8,
    val password: String = "password",
    val registry: String = DEFAULT_REGISTRY,
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
fun WithDsl.redis(configure: () -> RedisOptions = { RedisOptions() }): TestSystem = this.testSystem.withRedis(configure())

@StoveDsl
suspend fun ValidationDsl.redis(validation: suspend RedisSystem.() -> Unit): Unit = validation(this.testSystem.redis())

internal fun TestSystem.redis(): RedisSystem =
    getOrNone<RedisSystem>().getOrElse {
        throw SystemNotRegisteredException(RedisSystem::class)
    }

internal fun TestSystem.withRedis(options: RedisOptions = RedisOptions()): TestSystem =
    withProvidedRegistry(RedisContainer.DEFAULT_IMAGE_NAME.unversionedPart) {
        RedisContainer(it)
            .withCommand("redis-server", "--requirepass", options.password)
            .withReuse(this.options.keepDependenciesRunning)
    }.let { getOrRegister(RedisSystem(this, RedisContext(it, options))) }
        .let { this }