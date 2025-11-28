package com.trendyol.stove.testing.e2e.wiremock

import arrow.core.some
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.trendyol.stove.testing.e2e.system.TestSystem
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.intellij.lang.annotations.Language
import java.net.URI
import java.net.http.*
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers

class WireMockPartialMockingTest :
  FunSpec({

    val client = HttpClient.newBuilder().build()

    test("mockPostContaining should match requests containing specified fields") {
      val uniqueProductId = 12345
      val url = "/orders"

      TestSystem.validate {
        wiremock {
          mockPostContaining(
            url = url,
            requestContaining = mapOf("productId" to uniqueProductId),
            statusCode = 201,
            responseBody = mapOf("orderId" to "order-123", "status" to "created").some()
          )
        }
      }

      val requestBody = """{"productId": $uniqueProductId, "quantity": 5, "customerName": "John Doe"}"""
      val request = HttpRequest
        .newBuilder(URI("http://localhost:9098$url"))
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString(requestBody))
        .build()

      val response = client.send(request, BodyHandlers.ofString())
      response.statusCode() shouldBe 201
      response.body() shouldBe """{"orderId":"order-123","status":"created"}"""
    }

    test("mockPostContaining should match requests with multiple containing fields (AND logic)") {
      val productId = 999
      val customerId = "cust-abc"
      val url = "/orders/multi"

      TestSystem.validate {
        wiremock {
          mockPostContaining(
            url = url,
            requestContaining = mapOf(
              "productId" to productId,
              "customerId" to customerId
            ),
            statusCode = 200,
            responseBody = mapOf("matched" to true).some()
          )
        }
      }

      val requestBody = """{"productId": $productId, "customerId": "$customerId", "extra": "ignored"}"""
      val request = HttpRequest
        .newBuilder(URI("http://localhost:9098$url"))
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString(requestBody))
        .build()

      val response = client.send(request, BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.body() shouldBe """{"matched":true}"""
    }

    test("mockPostContaining should NOT match when one of multiple required fields is missing (AND logic)") {
      val url = "/orders/and-logic-test"

      TestSystem.validate {
        wiremock {
          // Stub expects BOTH productId AND customerId to match
          mockPostContaining(
            url = url,
            requestContaining = mapOf(
              "productId" to 123,
              "customerId" to "cust-required"
            ),
            statusCode = 200,
            responseBody = mapOf("matched" to true).some()
          )
        }
      }

      // Request only has productId, missing customerId - should NOT match
      val requestBody = """{"productId": 123, "extra": "data"}"""
      val request = HttpRequest
        .newBuilder(URI("http://localhost:9098$url"))
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString(requestBody))
        .build()

      val response = client.send(request, BodyHandlers.ofString())
      response.statusCode() shouldBe 404 // Not matched because customerId is missing
    }

    test("mockPostContaining should NOT match when field value is different (AND logic)") {
      val url = "/orders/and-logic-value-test"

      TestSystem.validate {
        wiremock {
          mockPostContaining(
            url = url,
            requestContaining = mapOf(
              "productId" to 123,
              "status" to "active"
            ),
            statusCode = 200,
            responseBody = mapOf("matched" to true).some()
          )
        }
      }

      // Request has both fields but status has wrong value - should NOT match
      val requestBody = """{"productId": 123, "status": "inactive"}"""
      val request = HttpRequest
        .newBuilder(URI("http://localhost:9098$url"))
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString(requestBody))
        .build()

      val response = client.send(request, BodyHandlers.ofString())
      response.statusCode() shouldBe 404 // Not matched because status value is different
    }

    test("mockPutContaining should match PUT requests containing specified fields") {
      val userId = "user-456"
      val url = "/users/456"

      TestSystem.validate {
        wiremock {
          mockPutContaining(
            url = url,
            requestContaining = mapOf("userId" to userId),
            statusCode = 200,
            responseBody = mapOf("updated" to true).some()
          )
        }
      }

      val requestBody = """{"userId": "$userId", "name": "Updated Name", "email": "test@example.com"}"""
      val request = HttpRequest
        .newBuilder(URI("http://localhost:9098$url"))
        .header("Content-Type", "application/json")
        .PUT(BodyPublishers.ofString(requestBody))
        .build()

      val response = client.send(request, BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.body() shouldBe """{"updated":true}"""
    }

    test("mockPatchContaining should match PATCH requests containing specified fields") {
      val status = "active"
      val url = "/users/789/status"

      TestSystem.validate {
        wiremock {
          mockPatchContaining(
            url = url,
            requestContaining = mapOf("status" to status),
            statusCode = 200,
            responseBody = mapOf("status" to status).some()
          )
        }
      }

      val requestBody = """{"status": "$status", "updatedBy": "admin", "timestamp": 1234567890}"""
      val request = HttpRequest
        .newBuilder(URI("http://localhost:9098$url"))
        .header("Content-Type", "application/json")
        .method("PATCH", BodyPublishers.ofString(requestBody))
        .build()

      val response = client.send(request, BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.body() shouldBe """{"status":"active"}"""
    }

    test("mockPostContaining should work with URL pattern matching") {
      val transactionId = "txn-unique-123"

      TestSystem.validate {
        wiremock {
          mockPostContaining(
            url = "/payments/.*",
            requestContaining = mapOf("transactionId" to transactionId),
            statusCode = 200,
            responseBody = mapOf("processed" to true).some(),
            urlPatternFn = { urlPathMatching(it) }
          )
        }
      }

      val requestBody = """{"transactionId": "$transactionId", "amount": 99.99}"""
      val request = HttpRequest
        .newBuilder(URI("http://localhost:9098/payments/credit-card"))
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString(requestBody))
        .build()

      val response = client.send(request, BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.body() shouldBe """{"processed":true}"""
    }

    test("mockPostContaining should support custom response headers") {
      val url = "/with-headers"

      TestSystem.validate {
        wiremock {
          mockPostContaining(
            url = url,
            requestContaining = mapOf("id" to 1),
            statusCode = 200,
            responseHeaders = mapOf("X-Custom-Header" to "CustomValue")
          )
        }
      }

      val requestBody = """{"id": 1, "extra": "data"}"""
      val request = HttpRequest
        .newBuilder(URI("http://localhost:9098$url"))
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString(requestBody))
        .build()

      val response = client.send(request, BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.headers().firstValue("X-Custom-Header").orElse("") shouldBe "CustomValue"
    }

    test("mockPostContaining should match nested objects") {
      val url = "/nested-objects"

      TestSystem.validate {
        wiremock {
          mockPostContaining(
            url = url,
            requestContaining = mapOf("user" to mapOf("id" to 123)),
            statusCode = 200,
            responseBody = mapOf("success" to true).some()
          )
        }
      }

      val requestBody = """{"user": {"id": 123, "name": "John"}, "action": "update"}"""
      val request = HttpRequest
        .newBuilder(URI("http://localhost:9098$url"))
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString(requestBody))
        .build()

      val response = client.send(request, BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.body() shouldBe """{"success":true}"""
    }

    test("mockPostContaining should match deeply nested objects with partial matching") {
      val url = "/deep-nested"

      TestSystem.validate {
        wiremock {
          mockPostContaining(
            url = url,
            requestContaining = mapOf(
              "order" to mapOf(
                "customer" to mapOf(
                  "id" to "cust-deep-123"
                )
              )
            ),
            statusCode = 200,
            responseBody = mapOf("deepMatched" to true).some()
          )
        }
      }

      // Request has extra fields at every level
      val requestBody = """{
        "order": {
          "id": "order-1",
          "customer": {
            "id": "cust-deep-123",
            "name": "Deep Customer",
            "email": "deep@example.com"
          },
          "items": [{"sku": "ABC"}]
        },
        "timestamp": 1234567890
      }"""
      val request = HttpRequest
        .newBuilder(URI("http://localhost:9098$url"))
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString(requestBody))
        .build()

      val response = client.send(request, BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.body() shouldBe """{"deepMatched":true}"""
    }

    test("mockPostContaining should match arrays in nested objects") {
      val url = "/nested-arrays"

      TestSystem.validate {
        wiremock {
          mockPostContaining(
            url = url,
            requestContaining = mapOf(
              "data" to mapOf(
                "tags" to listOf("important", "urgent")
              )
            ),
            statusCode = 200,
            responseBody = mapOf("arrayMatched" to true).some()
          )
        }
      }

      val requestBody = """{
        "data": {
          "tags": ["important", "urgent"],
          "other": "ignored"
        },
        "metadata": {}
      }"""
      val request = HttpRequest
        .newBuilder(URI("http://localhost:9098$url"))
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString(requestBody))
        .build()

      val response = client.send(request, BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.body() shouldBe """{"arrayMatched":true}"""
    }

    test("mockPutContaining should match deeply nested structures") {
      val url = "/deep-put"

      TestSystem.validate {
        wiremock {
          mockPutContaining(
            url = url,
            requestContaining = mapOf(
              "config" to mapOf(
                "settings" to mapOf(
                  "enabled" to true,
                  "level" to 5
                )
              )
            ),
            statusCode = 200,
            responseBody = mapOf("configured" to true).some()
          )
        }
      }

      val requestBody = """{
        "config": {
          "name": "test-config",
          "settings": {
            "enabled": true,
            "level": 5,
            "extra": "data"
          }
        }
      }"""
      val request = HttpRequest
        .newBuilder(URI("http://localhost:9098$url"))
        .header("Content-Type", "application/json")
        .PUT(BodyPublishers.ofString(requestBody))
        .build()

      val response = client.send(request, BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.body() shouldBe """{"configured":true}"""
    }

    test("mockPatchContaining should match complex nested structures") {
      val url = "/complex-patch"

      TestSystem.validate {
        wiremock {
          mockPatchContaining(
            url = url,
            requestContaining = mapOf(
              "update" to mapOf(
                "type" to "partial",
                "fields" to mapOf("status" to "active")
              )
            ),
            statusCode = 200,
            responseBody = mapOf("patched" to true).some()
          )
        }
      }

      val requestBody = """{
        "update": {
          "type": "partial",
          "fields": {
            "status": "active",
            "timestamp": 9999
          },
          "meta": {"source": "api"}
        }
      }"""
      val request = HttpRequest
        .newBuilder(URI("http://localhost:9098$url"))
        .header("Content-Type", "application/json")
        .method("PATCH", BodyPublishers.ofString(requestBody))
        .build()

      val response = client.send(request, BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.body() shouldBe """{"patched":true}"""
    }

    test("mockPostContaining should match single key in deep nested JSON using dot notation") {
      val url = "/deep-single-key"
      val deepCustomerId = "deep-cust-xyz"

      TestSystem.validate {
        wiremock {
          // Using dot notation to match a single key deep in the JSON
          mockPostContaining(
            url = url,
            requestContaining = mapOf("order.customer.id" to deepCustomerId),
            statusCode = 200,
            responseBody = mapOf("deepKeyMatched" to true).some()
          )
        }
      }

      val requestBody = """{
        "order": {
          "id": "order-999",
          "customer": {
            "id": "$deepCustomerId",
            "name": "Deep User",
            "address": {
              "city": "Istanbul"
            }
          },
          "items": [{"sku": "ITEM-1"}]
        },
        "metadata": {"source": "test"}
      }"""
      val request = HttpRequest
        .newBuilder(URI("http://localhost:9098$url"))
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString(requestBody))
        .build()

      val response = client.send(request, BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.body() shouldBe """{"deepKeyMatched":true}"""
    }

    test("mockPostContaining should match multiple single keys at different depths using dot notation") {
      val url = "/multi-deep-keys"

      TestSystem.validate {
        wiremock {
          mockPostContaining(
            url = url,
            requestContaining = mapOf(
              "order.customer.id" to "cust-multi-123",
              "order.payment.method" to "credit_card",
              "metadata.version" to 2
            ),
            statusCode = 200,
            responseBody = mapOf("multiDeepMatched" to true).some()
          )
        }
      }

      @Language("JSON")
      val requestBody = """{
        "order": {
          "id": "order-multi",
          "customer": {
            "id": "cust-multi-123",
            "name": "Multi Test"
          },
          "payment": {
            "method": "credit_card",
            "amount": 99.99
          }
        },
        "metadata": {
          "version": 2,
          "timestamp": 1234567890
        }
      }"""
      val request = HttpRequest
        .newBuilder(URI("http://localhost:9098$url"))
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString(requestBody))
        .build()

      val response = client.send(request, BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.body() shouldBe """{"multiDeepMatched":true}"""
    }

    test("mockPostContaining should match nested object at deep path using dot notation") {
      val url = "/deep-nested-object"

      TestSystem.validate {
        wiremock {
          // Match a nested object at a deep path
          mockPostContaining(
            url = url,
            requestContaining = mapOf(
              "data.config.settings" to mapOf("enabled" to true)
            ),
            statusCode = 200,
            responseBody = mapOf("deepObjectMatched" to true).some()
          )
        }
      }

      @Language("JSON")
      val requestBody = """{
        "data": {
          "config": {
            "name": "test",
            "settings": {
              "enabled": true,
              "level": 5,
              "extra": "ignored"
            }
          }
        }
      }"""
      val request = HttpRequest
        .newBuilder(URI("http://localhost:9098$url"))
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString(requestBody))
        .build()

      val response = client.send(request, BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.body() shouldBe """{"deepObjectMatched":true}"""
    }

    test("mockPutContaining should match deep key with dot notation") {
      val url = "/deep-put-key"

      TestSystem.validate {
        wiremock {
          mockPutContaining(
            url = url,
            requestContaining = mapOf("user.profile.settings.theme" to "dark"),
            statusCode = 200,
            responseBody = mapOf("themeUpdated" to true).some()
          )
        }
      }

      @Language("JSON")
      val requestBody = """{
        "user": {
          "id": "user-1",
          "profile": {
            "name": "Test User",
            "settings": {
              "theme": "dark",
              "notifications": true
            }
          }
        }
      }"""
      val request = HttpRequest
        .newBuilder(URI("http://localhost:9098$url"))
        .header("Content-Type", "application/json")
        .PUT(BodyPublishers.ofString(requestBody))
        .build()

      val response = client.send(request, BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.body() shouldBe """{"themeUpdated":true}"""
    }

    test("mockPatchContaining should match deep key with dot notation") {
      val url = "/deep-patch-key"

      TestSystem.validate {
        wiremock {
          mockPatchContaining(
            url = url,
            requestContaining = mapOf("document.section.paragraph.text" to "updated content"),
            statusCode = 200,
            responseBody = mapOf("textUpdated" to true).some()
          )
        }
      }

      @Language("JSON")
      val requestBody = """{
        "document": {
          "title": "My Doc",
          "section": {
            "id": 1,
            "paragraph": {
              "text": "updated content",
              "style": "normal"
            }
          }
        }
      }"""
      val request = HttpRequest
        .newBuilder(URI("http://localhost:9098$url"))
        .header("Content-Type", "application/json")
        .method("PATCH", BodyPublishers.ofString(requestBody))
        .build()

      val response = client.send(request, BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.body() shouldBe """{"textUpdated":true}"""
    }
  })
