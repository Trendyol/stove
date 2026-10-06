package stove.ktor.bff.tests

import arrow.core.None
import arrow.core.some
import com.trendyol.stove.http.*
import com.trendyol.stove.system.stove
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import stove.ktor.bff.fixtures.*

class GatewayTest : FunSpec({
  test("SSE authenticates the upstream request and carries the resume cursor") {
    stove { gatewayBrowser().shouldReceiveAuthenticatedEvents() }
  }

  test("WebSocket authenticates its HTTP handshake and logout ends the connection") {
    stove {
      val browser = gatewayBrowser()
      browser.shouldUseAuthenticatedWebSocket {
        http { postAndExpectBodilessResponse("/auth/logout", None, headers = browser.headers) { it.status shouldBe 204 } }
      }
    }
  }

  test("custom handlers transform upstream data while sibling paths keep proxying") {
    stove {
      val browser = gatewayBrowser()
      http {
        getResponse<Map<String, Any>>("/api/orders/summary", headers = browser.headers) {
          it.status shouldBe 200
          it.body() shouldBe mapOf("subject" to browser.subject, "total" to 1, "orderIds" to listOf("order-1001"))
        }
        getResponse<Map<String, Any>>("/api/orders/summary", queryParams = mapOf("status" to "pending"), headers = browser.headers) {
          it.status shouldBe 200
          it.body() shouldBe mapOf("subject" to browser.subject, "total" to 0, "orderIds" to emptyList<String>())
        }
        getResponse<Map<String, Any>>("/api/orders", headers = browser.headers) {
          it.status shouldBe 200
          it.body() shouldBe expectedOrders(browser.subject)
        }
      }
    }
  }

  test("custom validation can reject locally and still requires authentication") {
    stove {
      val browser = gatewayBrowser()
      http {
        getBodilessResponse("/api/orders/summary") { it.status shouldBe 401 }
        getBodilessResponse("/api/orders/summary", queryParams = mapOf("status" to "unsupported"), headers = browser.headers) {
          it.status shouldBe 400
        }
      }
    }
  }

  test("custom commands inherit CSRF and generate proofs for the rewritten upstream method and payload") {
    stove {
      val browser = gatewayBrowser()
      http {
        postAndExpectBodilessResponse("/api/orders/42/confirm", None) { it.status shouldBe 401 }
        postAndExpectBodilessResponse("/api/orders/42/confirm", None, headers = browser.headers - "X-CSRF-Token") { it.status shouldBe 403 }
        postAndExpectBodilessResponse("/api/orders/42/confirm", None, headers = browser.headers + ("Origin" to "https://another.example")) {
          it.status shouldBe 403
        }
        postAndExpectBody<Map<String, Any>>("/api/orders/42/confirm", headers = browser.headers + ("Idempotency-Key" to "confirm-42")) {
          it.status shouldBe 200
          val body = it.body()
          body["subject"] shouldBe browser.subject
          body["path"] shouldBe "/orders/42/confirmation"
          body["method"] shouldBe "PATCH"
          body["body"] shouldBe """{"status":"confirmed"}"""
          body["idempotencyKey"] shouldBe "confirm-42"
          body["cookieReceived"] shouldBe false
          body["csrfReceived"] shouldBe false
        }
      }
    }
  }

  test("configured aliases forward the subtree, query and authenticated identity") {
    stove {
      val browser = gatewayBrowser()
      http {
        for (prefix in listOf("/orders", "/api/orders", "/purchases")) {
          getResponse<Map<String, Any>>("$prefix/42/items", queryParams = mapOf("q" to "paid & shipped"), headers = browser.headers) {
            it.status shouldBe 200
            val body = it.body()
            body["subject"] shouldBe browser.subject
            body["path"] shouldBe "/orders/42/items"
            body["query"] shouldBe "paid & shipped"
            body["method"] shouldBe "GET"
          }
        }
        getResponse<Map<String, Any>>("/catalog/widget", headers = browser.headers) {
          it.status shouldBe 200
          it.body()["path"] shouldBe "/inventory/widget"
        }
        getResponse<Map<String, Any>>("/orders/archive/widget", headers = browser.headers) {
          it.status shouldBe 200
          it.body()["path"] shouldBe "/inventory/widget"
        }
        getResponse<Map<String, Any>>("/purchases/caf%C3%A9/items", headers = browser.headers) {
          it.status shouldBe 200
          it.body()["path"] shouldBe "/orders/caf%C3%A9/items"
        }
      }
    }
  }

  test("writes preserve bodies and application headers while replacing browser credentials") {
    stove {
      val browser = gatewayBrowser()
      val headers = browser.headers + mapOf(
        "Authorization" to "Bearer browser-invented",
        "DPoP" to "browser-invented",
        "Forwarded" to "host=attacker.example",
        "X-User-Id" to "admin",
        "Idempotency-Key" to "create-item-42"
      )
      http {
        postAndExpectBody<Map<String, Any>>("/purchases/42/items", "{\"sku\":\"widget\"}".some(), headers = headers) {
          it.status shouldBe 200
          val body = it.body()
          body["subject"] shouldBe browser.subject
          body["path"] shouldBe "/orders/42/items"
          body["method"] shouldBe "POST"
          body["body"] shouldBe "{\"sku\":\"widget\"}"
          body["idempotencyKey"] shouldBe "create-item-42"
          for (field in listOf("cookieReceived", "csrfReceived", "forwardedReceived", "spoofedUserReceived")) body[field] shouldBe false
        }
        patchAndExpectBodilessResponse("/purchases/42", None, headers = browser.headers) { it.status shouldBe 204 }
        deleteAndExpectBodilessResponse("/purchases/42", headers = browser.headers) { it.status shouldBe 204 }
      }
    }
  }

  test("authentication and CSRF protect every configured write route") {
    stove {
      val browser = gatewayBrowser()
      http {
        getBodilessResponse("/orders/42/items") { it.status shouldBe 401 }
        postAndExpectBodilessResponse("/purchases/42/items", None) { it.status shouldBe 401 }
        postAndExpectBodilessResponse("/purchases/42/items", None, headers = browser.headers - "X-CSRF-Token") { it.status shouldBe 403 }
        postAndExpectBodilessResponse("/purchases/42/items", None, headers = browser.headers + ("Origin" to "https://another.example")) {
          it.status shouldBe 403
        }
        postAndExpectBodilessResponse("/purchases/42/items", None, headers = browser.headers) { it.status shouldBe 200 }
      }
    }
  }

  test("method policies and route boundaries are enforced before forwarding") {
    stove {
      val browser = gatewayBrowser()
      http {
        postAndExpectBodilessResponse("/catalog/widget", None, headers = browser.headers) {
          it.status shouldBe 405
          it.header("Allow") shouldBe "GET, HEAD"
        }
        getBodilessResponse("/orders-unconfigured/42", headers = browser.headers) { it.status shouldBe 404 }
        getBodilessResponse("/unconfigured", headers = browser.headers) { it.status shouldBe 404 }
      }
    }
  }

  test("request and response limits fail before exposing oversized content") {
    stove {
      val browser = gatewayBrowser()
      http {
        postAndExpectBodilessResponse("/limited/42/items", "x".repeat(128).some(), headers = browser.headers) { it.status shouldBe 413 }
        getBodilessResponse("/limited/large", headers = browser.headers) { it.status shouldBe 502 }
        getBodilessResponse("/limited/challenge", headers = browser.headers) { it.status shouldBe 502 }
      }
    }
  }

  test("upstream timeouts and redirects are translated at the gateway boundary") {
    stove {
      val browser = gatewayBrowser()
      http {
        getBodilessResponse("/slow-orders/slow", headers = browser.headers) { it.status shouldBe 504 }
        getBodilessResponse("/orders/redirect", headers = browser.headers) {
          it.status shouldBe 502
          it.headers.keys.none { name -> name.equals("Location", ignoreCase = true) } shouldBe true
        }
        getBodilessResponse("/api/session", headers = browser.headers) { it.status shouldBe 200 }
      }
    }
  }

  test("response allowlists preserve metadata and isolate upstream cookies") {
    stove {
      val browser = gatewayBrowser()
      http {
        getResponse<String>("/orders/headers", headers = browser.headers) {
          it.status shouldBe 200
          it.body() shouldBe "headers"
          it.header("ETag") shouldBe "\"v1\""
          it.header("Cache-Control") shouldBe "no-store"
          it.headers.keys.none { name -> name.equals("Set-Cookie", ignoreCase = true) } shouldBe true
          it.headers.keys.none { name -> name.equals("X-Internal-Secret", ignoreCase = true) } shouldBe true
        }
      }
    }
  }

  test("HEAD and conditional responses preserve representation metadata without a body") {
    stove {
      val browser = gatewayBrowser()
      http {
        shouldReturnOrdersMetadata(browser.headers)
        shouldReturnNotModifiedOrders(browser.headers)
      }
    }
  }
})
