package com.trendyol.stove.examples.kotlin.ktor.e2e.tests

import com.trendyol.stove.http.http
import com.trendyol.stove.system.stove
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class IndexTests :
  FunSpec({
    test("Index page should return 200") {
      stove {
        http {
          getResponse<String>("/") { actual ->
            actual.status shouldBe 200
            actual.body() shouldBe "Hello, World!"
          }
        }
      }
    }
  })
