package com.trendyol.stove.mysql

import com.trendyol.stove.rdbms.RelationalDatabaseExposedConfiguration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class MySqlOptionsTest :
  FunSpec({

    test("MySqlOptions.provided should create ProvidedMySqlOptions with correct config") {
      val options = MySqlOptions.provided(
        jdbcUrl = "jdbc:mysql://localhost:3306/testdb",
        host = "localhost",
        port = 3306,
        databaseName = "testdb",
        username = "root",
        password = "rootpass",
        configureExposedConfiguration = { cfg ->
          listOf("spring.datasource.url=${cfg.jdbcUrl}")
        }
      )

      options.providedConfig.jdbcUrl shouldBe "jdbc:mysql://localhost:3306/testdb"
      options.providedConfig.host shouldBe "localhost"
      options.providedConfig.port shouldBe 3306
      options.providedConfig.username shouldBe "root"
      options.providedConfig.password shouldBe "rootpass"
      options.runMigrationsForProvided shouldBe true
    }

    test("ProvidedMySqlOptions should expose correct properties") {
      val config = RelationalDatabaseExposedConfiguration(
        jdbcUrl = "jdbc:mysql://remote:3306/db",
        host = "remote",
        port = 3306,
        username = "user",
        password = "pass"
      )
      val options = ProvidedMySqlOptions(
        config = config,
        databaseName = "db",
        runMigrations = false,
        configureExposedConfiguration = { _ -> listOf() }
      )

      options.config shouldBe config
      options.providedConfig shouldBe config
      options.runMigrationsForProvided shouldBe false
    }

    test("MySqlOptions should have sensible defaults") {
      val options = object : MySqlOptions(
        configureExposedConfiguration = { _ -> listOf() }
      ) {}

      options.databaseName shouldBe "stove"
      options.username shouldBe "sa"
      options.password shouldBe "sa"
      options.container shouldNotBe null
    }

    test("MySqlContainerOptions should have defaults") {
      val opts = MySqlContainerOptions()
      opts.image shouldBe DEFAULT_MYSQL_IMAGE_NAME
      opts.tag shouldBe "8.4"
    }
  })
