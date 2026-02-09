package com.trendyol.stove.mssql

import com.trendyol.stove.rdbms.RelationalDatabaseExposedConfiguration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class MsSqlOptionsTest :
  FunSpec({

    test("MsSqlOptions.provided should create ProvidedMsSqlOptions with correct config") {
      val options = MsSqlOptions.provided(
        jdbcUrl = "jdbc:sqlserver://localhost:1433;databaseName=testdb",
        host = "localhost",
        port = 1433,
        applicationName = "myapp",
        databaseName = "testdb",
        userName = "sa",
        password = "sapass",
        configureExposedConfiguration = { cfg ->
          listOf("spring.datasource.url=${cfg.jdbcUrl}")
        }
      )

      options.providedConfig.jdbcUrl shouldBe "jdbc:sqlserver://localhost:1433;databaseName=testdb"
      options.providedConfig.host shouldBe "localhost"
      options.providedConfig.port shouldBe 1433
      options.providedConfig.username shouldBe "sa"
      options.providedConfig.password shouldBe "sapass"
      options.runMigrationsForProvided shouldBe true
    }

    test("ProvidedMsSqlOptions should expose correct properties") {
      val config = RelationalDatabaseExposedConfiguration(
        jdbcUrl = "jdbc:sqlserver://remote:1433",
        host = "remote",
        port = 1433,
        username = "user",
        password = "pass"
      )
      val options = ProvidedMsSqlOptions(
        config = config,
        applicationName = "app",
        databaseName = "db",
        userName = "user",
        password = "pass",
        runMigrations = false,
        configureExposedConfiguration = { _ -> listOf() }
      )

      options.config shouldBe config
      options.providedConfig shouldBe config
      options.runMigrationsForProvided shouldBe false
    }

    test("MssqlContainerOptions should have defaults") {
      val opts = MssqlContainerOptions()
      opts.tag shouldBe "2022-latest"
      opts.toolsPath shouldBe ToolsPath.After2019
    }

    test("ToolsPath sealed class variants") {
      ToolsPath.Before2019.path shouldBe "mssql-tools"
      ToolsPath.After2019.path shouldBe "mssql-tools18"
      ToolsPath.Custom("custom-path").path shouldBe "custom-path"

      ToolsPath.Before2019.toString() shouldBe "mssql-tools"
      ToolsPath.After2019.toString() shouldBe "mssql-tools18"
    }

    test("MsSqlOptions should require application and database name") {
      val options = object : MsSqlOptions(
        applicationName = "test-app",
        databaseName = "test-db",
        userName = "sa",
        password = "sapass",
        configureExposedConfiguration = { _ -> listOf() }
      ) {}

      options.applicationName shouldBe "test-app"
      options.databaseName shouldBe "test-db"
      options.userName shouldBe "sa"
      options.password shouldBe "sapass"
      options.container shouldNotBe null
    }
  })
