package com.trendyol.stove.postgres

import com.trendyol.stove.rdbms.RelationalDatabaseExposedConfiguration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class PostgresqlOptionsTest :
  FunSpec({

    test("PostgresqlOptions.provided should create ProvidedPostgresqlOptions with correct config") {
      val options = PostgresqlOptions.provided(
        jdbcUrl = "jdbc:postgresql://localhost:5432/testdb",
        host = "localhost",
        port = 5432,
        databaseName = "testdb",
        username = "postgres",
        password = "pgpass",
        configureExposedConfiguration = { cfg ->
          listOf("spring.datasource.url=${cfg.jdbcUrl}")
        }
      )

      options.providedConfig.jdbcUrl shouldBe "jdbc:postgresql://localhost:5432/testdb"
      options.providedConfig.host shouldBe "localhost"
      options.providedConfig.port shouldBe 5432
      options.providedConfig.username shouldBe "postgres"
      options.providedConfig.password shouldBe "pgpass"
      options.runMigrationsForProvided shouldBe true
    }

    test("ProvidedPostgresqlOptions should expose correct properties") {
      val config = RelationalDatabaseExposedConfiguration(
        jdbcUrl = "jdbc:postgresql://remote:5432/db",
        host = "remote",
        port = 5432,
        username = "user",
        password = "pass"
      )
      val options = ProvidedPostgresqlOptions(
        config = config,
        databaseName = "db",
        runMigrations = false,
        configureExposedConfiguration = { _ -> listOf() }
      )

      options.config shouldBe config
      options.providedConfig shouldBe config
      options.runMigrationsForProvided shouldBe false
    }

    test("PostgresqlOptions should have sensible defaults") {
      val options = object : PostgresqlOptions(
        configureExposedConfiguration = { _ -> listOf() }
      ) {}

      options.databaseName shouldBe "stove"
      options.username shouldBe "sa"
      options.password shouldBe "sa"
      options.container shouldNotBe null
    }

    test("PostgresqlContainerOptions should have defaults") {
      val opts = PostgresqlContainerOptions()
      opts.image shouldBe DEFAULT_POSTGRES_IMAGE_NAME
      opts.tag shouldBe "latest"
    }
  })
