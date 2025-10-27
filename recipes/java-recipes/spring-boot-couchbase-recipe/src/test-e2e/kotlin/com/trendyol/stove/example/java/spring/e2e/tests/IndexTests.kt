package com.trendyol.stove.example.java.spring.e2e.tests

import com.trendyol.stove.testing.e2e.http.http
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class IndexTests :
  FunSpec({
    test("Index page should be accessible") {
      validate {
        http {
          get<String>("/") { actual ->
            actual shouldBe "Hello, World!"
          }
        }
      }
    }
  })
