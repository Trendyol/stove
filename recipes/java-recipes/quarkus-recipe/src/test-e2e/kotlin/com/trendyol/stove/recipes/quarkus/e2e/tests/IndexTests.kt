package com.trendyol.stove.recipes.quarkus.e2e.tests

import com.trendyol.stove.testing.e2e.http.http
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class IndexTests :
  FunSpec({
    test("Index page should return 200") {
      validate {
        http {
          get<String>(
            "/hello",
            headers = mapOf(
              "Content-Type" to "text/plain",
              "Accept" to "text/plain"
            )
          ) { actual ->
            actual shouldBe "Hello from Quarkus REST"
          }
        }
      }
    }
  })
