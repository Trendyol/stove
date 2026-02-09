package com.trendyol.stove.redis

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

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
