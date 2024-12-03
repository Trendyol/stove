package com.trendyol.stove.recipes.quarkus.e2e.tests

import com.trendyol.stove.recipes.quarkus.HelloService
import com.trendyol.stove.testing.e2e.http.http
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import com.trendyol.stove.testing.e2e.system.using
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class IndexTests : FunSpec({
  test("Index page should return 200") {
    validate {
      using<HelloService> {
        println(this)
      }

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
