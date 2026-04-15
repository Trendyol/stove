package com.trendyol.stove.recipes.quarkus.e2e.tests

import com.trendyol.stove.http.http
import com.trendyol.stove.system.stove
import com.trendyol.stove.tracing.tracing
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class IndexTests :
  FunSpec({

    test("Index page should return 200") {
      stove {
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

    test("tracing should capture quarkus request flow") {
      stove {
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

        tracing {
          val spans = waitForSpans(expectedCount = 2, timeoutMs = 10_000)

          spans.isNotEmpty() shouldBe true
          spanCountShouldBeAtLeast(2)
          spans.any { span ->
            span.operationName.contains("/hello") ||
              span.attributes.values.any { value -> value.contains("/hello") }
          } shouldBe true
        }
      }
    }
  })
