package com.trendyol.stove.example.java.spring.e2e.tests

import com.trendyol.stove.http.http
import com.trendyol.stove.system.stove
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class IndexTests :
  FunSpec({
    test("Index page should be accessible") {
      stove {
        http {
          get<String>("/") { actual ->
            actual shouldBe "Hello, World!"
          }
        }
      }
    }
  })
