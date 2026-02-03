package com.trendyol.stove.rdbms

import com.trendyol.stove.system.abstractions.ProvidedRuntime
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RelationalDatabaseContextTest :
  FunSpec({
    test("should hold runtime and configuration mapper") {
      val context = object : RelationalDatabaseContext(
        runtime = ProvidedRuntime,
        configureExposedConfiguration = { cfg -> listOf("jdbc=${cfg.jdbcUrl}") }
      ) {}

      context.runtime shouldBe ProvidedRuntime
      context.configureExposedConfiguration(
        RelationalDatabaseExposedConfiguration("jdbc:h2:mem:test", "localhost", 0, "", "sa")
      ) shouldBe listOf("jdbc=jdbc:h2:mem:test")
    }
  })
