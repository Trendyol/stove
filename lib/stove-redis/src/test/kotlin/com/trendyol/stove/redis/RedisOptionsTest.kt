package com.trendyol.stove.redis

import com.trendyol.stove.chaos.TcpEndpoint
import com.trendyol.stove.chaos.enableChaos
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

class RedisOptionsTest :
  FunSpec({

    test("RedisExposedConfiguration should hold connection details") {
      val cfg = RedisExposedConfiguration(
        host = "localhost",
        port = 6379,
        redisUri = "redis://localhost:6379",
        database = "8",
        password = "secret"
      )

      cfg.host shouldBe "localhost"
      cfg.port shouldBe 6379
      cfg.redisUri shouldBe "redis://localhost:6379"
      cfg.database shouldBe "8"
      cfg.password shouldBe "secret"
    }

    test("RedisOptions.provided should create ProvidedRedisOptions with correct config") {
      val options = RedisOptions.provided(
        host = "redis-host",
        port = 6379,
        password = "pass",
        database = 5,
        configureExposedConfiguration = { cfg ->
          listOf("redis.host=${cfg.host}", "redis.port=${cfg.port}")
        }
      )

      options.providedConfig.host shouldBe "redis-host"
      options.providedConfig.port shouldBe 6379
      options.providedConfig.redisUri shouldBe "redis://redis-host:6379"
      options.providedConfig.database shouldBe "5"
      options.providedConfig.password shouldBe "pass"
      options.runMigrationsForProvided shouldBe true
    }

    test("provided IPv6 options produce a valid single-server URI for chaos routing") {
      val options = RedisOptions.provided("::1", 6379, "password", configureExposedConfiguration = { emptyList() })
      options.providedConfig.redisUri shouldBe "redis://[::1]:6379"
      RedisChaosAdapter.reroute(options.providedConfig) { it }.redisUri shouldBe "redis://[::1]:6379"
    }

    test("chaos opt-in preserves provided options and composes with migrations in either order") {
      val original = RedisOptions.provided(
        "localhost",
        6379,
        "password",
        database = 3,
        runMigrations = false,
        configureExposedConfiguration = { listOf("redis.uri=${it.redisUri}") }
      )
      val enabled: ProvidedRedisOptions = original.enableChaos()
      enabled shouldBeSameInstanceAs original
      enabled.runMigrationsForProvided shouldBe false
      enabled.providedConfig.database shouldBe "3"
      enabled.configureExposedConfiguration(enabled.providedConfig) shouldBe listOf("redis.uri=redis://localhost:6379")

      var configured = 0
      enabled.migrations { configured++ }.enableChaos() shouldBeSameInstanceAs original
      enabled.enableChaos().migrations { configured++ } shouldBeSameInstanceAs original
      configured shouldBe 2
      original.chaosOptions.enabled shouldBe true
      RedisOptions { emptyList() }.chaosOptions.enabled shouldBe false
    }

    test("ProvidedRedisOptions should expose correct properties") {
      val config = RedisExposedConfiguration(
        host = "remote",
        port = 6380,
        redisUri = "redis://remote:6380",
        database = "3",
        password = "p"
      )
      val options = ProvidedRedisOptions(
        config = config,
        database = 3,
        password = "p",
        runMigrations = false,
        configureExposedConfiguration = { _ -> listOf() }
      )

      options.config shouldBe config
      options.providedConfig shouldBe config
      options.runMigrationsForProvided shouldBe false
    }

    test("RedisOptions should have sensible defaults") {
      val options = object : RedisOptions(
        configureExposedConfiguration = { _ -> listOf() }
      ) {}

      options.database shouldBe 8
      options.password shouldBe "password"
      options.container shouldNotBe null
    }
  })
