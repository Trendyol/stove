package com.trendyol.stove.recipes.quarkus.e2e.tests

import com.trendyol.stove.recipes.quarkus.HelloService
import com.trendyol.stove.recipes.quarkus.e2e.setup.runningOnCI
import com.trendyol.stove.testing.e2e.http.http
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import com.trendyol.stove.testing.e2e.system.using
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class IndexTests :
  FunSpec({
    xtest("Index page should return 200") {
      validate {
        http {
          get<String>(
            "/hello",
            headers = mapOf(
              "Content-Type" to "text/plain",
              "Accept" to "text/plain"
            )
          ) { actual ->
            actual shouldBe "Hello from Quarkus Service"
          }
        }
      }
    }

    /**
     * For now not supported, and disabled on CI, run it when you want to test the bridge
     */
    xtest("bridge should work").config(enabled = !runningOnCI) {
      validate {
        using<HelloService> {
          println(this)
        }
      }
    }
  })
