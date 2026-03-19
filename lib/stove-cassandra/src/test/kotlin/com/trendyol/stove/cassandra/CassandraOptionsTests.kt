package com.trendyol.stove.cassandra

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

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
      options.container shouldNotBe null
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
