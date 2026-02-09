package com.trendyol.stove.mongodb

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class MongodbOptionsTests :
  FunSpec({

    test("MongodbExposedConfiguration should hold connection details") {
      val cfg = MongodbExposedConfiguration(
        connectionString = "mongodb://localhost:27017",
        host = "localhost",
        port = 27017,
        replicaSetUrl = "mongodb://localhost:27017/replicaSet"
      )

      cfg.connectionString shouldBe "mongodb://localhost:27017"
      cfg.host shouldBe "localhost"
      cfg.port shouldBe 27017
      cfg.replicaSetUrl shouldBe "mongodb://localhost:27017/replicaSet"
    }

    test("MongodbSystemOptions.provided should create ProvidedMongodbSystemOptions") {
      val options = MongodbSystemOptions.provided(
        connectionString = "mongodb://localhost:27017",
        host = "localhost",
        port = 27017,
        configureExposedConfiguration = { cfg ->
          listOf("mongo.uri=${cfg.connectionString}")
        }
      )

      options.providedConfig.connectionString shouldBe "mongodb://localhost:27017"
      options.providedConfig.host shouldBe "localhost"
      options.providedConfig.port shouldBe 27017
      options.providedConfig.replicaSetUrl shouldBe "mongodb://localhost:27017"
      options.runMigrationsForProvided shouldBe true
    }

    test("ProvidedMongodbSystemOptions should expose correct properties") {
      val config = MongodbExposedConfiguration(
        connectionString = "mongodb://remote:27017",
        host = "remote",
        port = 27017,
        replicaSetUrl = "mongodb://remote:27017"
      )
      val options = ProvidedMongodbSystemOptions(
        config = config,
        runMigrations = false,
        configureExposedConfiguration = { _ -> listOf("test=true") }
      )

      options.config shouldBe config
      options.providedConfig shouldBe config
      options.runMigrationsForProvided shouldBe false
    }

    test("MongodbSystemOptions should have sensible defaults") {
      val options = object : MongodbSystemOptions(
        configureExposedConfiguration = { _ -> listOf() }
      ) {}

      options.databaseOptions shouldNotBe null
      options.container shouldNotBe null
      options.serde shouldNotBe null
      options.jsonWriterSettings shouldNotBe null
    }

    test("StoveMongoJsonWriterSettings.objectIdAsString should be configured") {
      StoveMongoJsonWriterSettings.objectIdAsString shouldNotBe null
    }
  })
