package com.trendyol.stove.recipes.quarkus.e2e.tests

import arrow.core.some
import com.trendyol.stove.http.http
import com.trendyol.stove.recipes.quarkus.InventorySummary
import com.trendyol.stove.recipes.quarkus.Item
import com.trendyol.stove.recipes.quarkus.ItemRepository
import com.trendyol.stove.system.stove
import com.trendyol.stove.system.using
import com.trendyol.stove.tracing.tracing
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class IndexTests :
  FunSpec({

    test("inventory state is shared between CDI Bridge and HTTP") {
      val id = UUID.randomUUID().toString()
      stove {
        using<ItemRepository> {
          addItem(Item(id, "Coffee beans"))
          val item: Item = getItemById(id)
          item.id shouldBe id
          item.name shouldBe "Coffee beans"
        }
        http {
          get<String>("/items/$id", headers = mapOf("Accept" to "text/plain")) {
            it shouldBe "Coffee beans"
          }
          putAndExpectBodilessResponse(
            "/items/$id",
            body = "Roasted coffee beans".some(),
            headers = mapOf("Content-Type" to "text/plain")
          ) { it.status shouldBe 204 }
        }
        using<ItemRepository> {
          val updated: Item = getItemById(id)
          updated.id shouldBe id
          updated.name shouldBe "Roasted coffee beans"
        }
        // No HTTP resource injects this bean: e2e augmentation must preserve it.
        using<InventorySummary> {
          contains(id) shouldBe true
          contains(UUID.randomUUID().toString()) shouldBe false
        }
      }
    }

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
