package com.trendyol.stove.examples.kotlin.ktor.e2e.tests

import com.trendyol.stove.testing.e2e.http.http
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class IndexTests : FunSpec({
  test("Index page should return 200") {
    validate {
      http {
        getResponse<String>("/") { actual ->
          actual.status shouldBe 200
          actual.body() shouldBe "Hello, World!"
        }
      }
    }
  }
})
