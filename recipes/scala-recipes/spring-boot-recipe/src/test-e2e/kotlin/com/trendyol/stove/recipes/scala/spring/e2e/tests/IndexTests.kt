package com.trendyol.stove.recipes.scala.spring.e2e.tests

import com.trendyol.stove.recipes.scala.spring.CurrentThreadRetriever
import com.trendyol.stove.testing.e2e.http.http
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import com.trendyol.stove.testing.e2e.system.using
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

class IndexTests : FunSpec({
  test("Index page should be accessible") {
    validate {
      http {
        get<String>("/hello") { actual ->
          actual shouldContain "Hello, World! from reactor"
        }
      }
    }
  }

  test("bridge should work") {
    validate {
      using<CurrentThreadRetriever> {
        this.currentThreadName shouldNotBe ""
        println(this.currentThreadName)
      }
    }
  }
})
