package com.trendyol.stove.couchbase

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class CouchbaseOptionsTest :
  FunSpec({

    test("CouchbaseExposedConfiguration should hold connection details") {
      val cfg = CouchbaseExposedConfiguration(
        connectionString = "couchbase://localhost:8091",
        hostsWithPort = "localhost:8091",
        username = "admin",
        password = "password"
      )

      cfg.connectionString shouldBe "couchbase://localhost:8091"
      cfg.hostsWithPort shouldBe "localhost:8091"
      cfg.username shouldBe "admin"
      cfg.password shouldBe "password"
    }

    test("CouchbaseSystemOptions.provided should create ProvidedCouchbaseSystemOptions") {
      val options = CouchbaseSystemOptions.provided(
        connectionString = "couchbase://cb-host:8091",
        username = "admin",
        password = "pass",
        defaultBucket = "test-bucket",
        configureExposedConfiguration = { cfg ->
          listOf("couchbase.hosts=${cfg.hostsWithPort}")
        }
      )

      options.providedConfig.connectionString shouldBe "couchbase://cb-host:8091"
      options.providedConfig.hostsWithPort shouldBe "cb-host:8091"
      options.providedConfig.username shouldBe "admin"
      options.providedConfig.password shouldBe "pass"
      options.runMigrationsForProvided shouldBe true
    }

    test("CouchbaseSystemOptions.provided should strip couchbase:// prefix for hostsWithPort") {
      val options = CouchbaseSystemOptions.provided(
        connectionString = "couchbase://node1:8091,node2:8091",
        username = "u",
        password = "p",
        defaultBucket = "b",
        configureExposedConfiguration = { _ -> listOf() }
      )

      options.providedConfig.hostsWithPort shouldBe "node1:8091,node2:8091"
    }

    test("ProvidedCouchbaseSystemOptions should expose correct properties") {
      val config = CouchbaseExposedConfiguration(
        connectionString = "couchbase://remote:8091",
        hostsWithPort = "remote:8091",
        username = "u",
        password = "p"
      )
      val options = ProvidedCouchbaseSystemOptions(
        config = config,
        defaultBucket = "bucket",
        runMigrations = false,
        configureExposedConfiguration = { _ -> listOf() }
      )

      options.config shouldBe config
      options.providedConfig shouldBe config
      options.runMigrationsForProvided shouldBe false
    }

    test("CouchbaseContainerOptions should have defaults") {
      val opts = CouchbaseContainerOptions()
      opts.image shouldBe "couchbase/server"
      opts.tag shouldBe "latest"
    }

    test("CouchbaseSystemOptions should have sensible defaults") {
      val options = object : CouchbaseSystemOptions(
        defaultBucket = "default",
        configureExposedConfiguration = { _ -> listOf() }
      ) {}

      options.defaultBucket shouldBe "default"
      options.containerOptions shouldNotBe null
      options.clusterSerDe shouldNotBe null
      options.clusterTranscoder shouldNotBe null
    }
  })
