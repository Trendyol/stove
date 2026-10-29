package com.trendyol.stove.redis

import com.trendyol.stove.chaos.*
import com.trendyol.stove.redis.RedisSystem.Companion.client
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.SystemKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class RedisChaosDslTest : FunSpec({
  test("shuts down native Redis resources when cleanup fails") {
    val cleanupFailure = IllegalStateException("cleanup failed")
    val fixture = RedisRegistrationFixture(cleanupFailure)
    val client = fixture.startDirectClient()

    shouldThrow<IllegalStateException> { fixture.close() } shouldBe cleanupFailure
    client.resources.eventExecutorGroup().isTerminated shouldBe true
  }

  test("routes the URI authority while preserving TLS, encoded credentials and database") {
    val direct = RedisExposedConfiguration("redis", 6380, "rediss://user:p%40ss@redis:6380/8?timeout=1s", "8", "secret")
    RedisChaosAdapter.applicationConfiguration(direct, TcpEndpoint("::1", 12345)) shouldBe direct.copy(
      host = "::1",
      port = 12345,
      redisUri = "rediss://user:p%40ss@[::1]:12345/8?timeout=1s"
    )
    RedisChaosAdapter.endpoint(direct) shouldBe TcpEndpoint("redis", 6380)
  }

  test("rejects sentinel and mismatched endpoint configurations") {
    listOf("redis-sentinel://redis:6379", "redis://other:6379", "redis://redis:6380").forEach { uri ->
      shouldThrow<IllegalArgumentException> {
        RedisChaosAdapter.endpoint(RedisExposedConfiguration("redis", 6379, uri, "0", "p"))
      }
    }
  }

  test("enabling chaos for the primary key preserves the default Redis configuration") {
    RedisRegistrationFixture().use { fixture ->
      val original = fixture.direct
      val primary = fixture.primaryApplicationConfiguration()

      primary.port shouldNotBe original.port
      primary.redisUri shouldBe "redis://127.0.0.1:${primary.port}"
      fixture.defaultApplicationConfiguration() shouldBe original
      fixture.direct shouldBe original
    }
  }
})

private object PrimaryRedis : SystemKey

private class RedisRegistrationFixture(cleanupFailure: RuntimeException? = null) : AutoCloseable {
  private val stove = Stove()
  private val options = RedisOptions.provided(
    host = "127.0.0.1",
    port = 6379,
    password = "secret",
    runMigrations = false,
    cleanup = { if (cleanupFailure != null) throw cleanupFailure },
    configureExposedConfiguration = { emptyList() }
  )
  val direct = options.providedConfig

  init {
    stove.with {
      redis { options }
      redis(PrimaryRedis) {
        enableChaos()
        options
      }
    }
  }

  suspend fun startDirectClient(): io.lettuce.core.RedisClient {
    stove.redis().run()
    return stove.redis().client()
  }

  fun defaultApplicationConfiguration() = stove.redis().applicationConfiguration(direct, RedisChaosAdapter)

  fun primaryApplicationConfiguration() = stove.redis(PrimaryRedis).applicationConfiguration(direct, RedisChaosAdapter)

  override fun close() = stove.close()
}
