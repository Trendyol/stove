package com.trendyol.stove.examples.kotlin.ktor.e2e.tests.configuration

import com.trendyol.stove.examples.kotlin.ktor.application.RecipeAppConfig
import com.trendyol.stove.system.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe

class ConfigurationTests :
  FunSpec({
    test("configuration can be changed from app") {
      stove {
        using<RecipeAppConfig> {
          this.server.name shouldNotBe "test"
        }
      }
    }
  })
