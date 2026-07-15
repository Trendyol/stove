package com.trendyol.stove.cassandra

import com.datastax.oss.driver.api.core.config.DefaultDriverOption
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class CassandraOptionsTests :
  FunSpec({

    test("CassandraExposedConfiguration should hold connection details") {
      val cfg = CassandraExposedConfiguration(
        host = "localhost",
        port = 9042,
        datacenter = "datacenter1",
        keyspace = "my_keyspace"
      )

      cfg.host shouldBe "localhost"
      cfg.port shouldBe 9042
      cfg.datacenter shouldBe "datacenter1"
      cfg.keyspace shouldBe "my_keyspace"
    }

    test("CassandraSystemOptions.provided should create ProvidedCassandraSystemOptions with correct config") {
      val options = CassandraSystemOptions.provided(
        host = "cassandra-host",
        port = 9042,
        datacenter = "datacenter1",
        keyspace = "test_keyspace",
        configureExposedConfiguration = { cfg ->
          listOf(
            "cassandra.contact-points=${cfg.host}:${cfg.port}",
            "cassandra.local-datacenter=${cfg.datacenter}"
          )
        }
      )

      options.providedConfig.host shouldBe "cassandra-host"
      options.providedConfig.port shouldBe 9042
      options.providedConfig.datacenter shouldBe "datacenter1"
      options.providedConfig.keyspace shouldBe "test_keyspace"
      options.runMigrationsForProvided shouldBe true
    }

    test("ProvidedCassandraSystemOptions should expose correct properties") {
      val config = CassandraExposedConfiguration(
        host = "remote",
        port = 9043,
        datacenter = "dc1",
        keyspace = "prod_keyspace"
      )
      val options = ProvidedCassandraSystemOptions(
        config = config,
        keyspace = "prod_keyspace",
        datacenter = "dc1",
        runMigrations = false,
        configureExposedConfiguration = { _ -> listOf() }
      )

      options.config shouldBe config
      options.providedConfig shouldBe config
      options.runMigrationsForProvided shouldBe false
    }

    test("CassandraSystemOptions should have sensible defaults") {
      val options = object : CassandraSystemOptions(
        configureExposedConfiguration = { _ -> listOf() }
      ) {}

      options.keyspace shouldBe "stove"
      options.datacenter shouldBe "datacenter1"
      options.requestTimeout shouldBe 30.seconds
      options.container shouldNotBe null
    }

    test("CassandraSystemOptions should allow configuring the request timeout") {
      val options = CassandraSystemOptions(
        configureExposedConfiguration = { _ -> listOf() }
      ).requestTimeout(45.seconds)

      options.requestTimeout shouldBe 45.seconds
      options.createDriverConfigLoader().use { loader ->
        loader.initialConfig.defaultProfile.getDuration(DefaultDriverOption.REQUEST_TIMEOUT) shouldBe
          45.seconds.toJavaDuration()
      }
    }

    test("CassandraSystemOptions should reject invalid request timeouts") {
      val options = CassandraSystemOptions(
        configureExposedConfiguration = { _ -> listOf() }
      )

      shouldThrow<IllegalArgumentException> {
        options.requestTimeout(Duration.ZERO)
      }
    }

    test("CassandraSystemOptions.provided should default port to 9042") {
      val options = CassandraSystemOptions.provided(
        host = "localhost",
        keyspace = "test",
        configureExposedConfiguration = { _ -> listOf() }
      )

      options.providedConfig.port shouldBe 9042
    }

    test("CassandraSystemOptions.provided should default datacenter to datacenter1") {
      val options = CassandraSystemOptions.provided(
        host = "localhost",
        keyspace = "test",
        configureExposedConfiguration = { _ -> listOf() }
      )

      options.providedConfig.datacenter shouldBe "datacenter1"
    }
  })
