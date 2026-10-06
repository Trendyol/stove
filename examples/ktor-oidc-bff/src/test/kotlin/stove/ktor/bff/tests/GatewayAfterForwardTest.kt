package stove.ktor.bff.tests

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import kotlinx.coroutines.yield
import stove.ktor.bff.fixtures.*
import stove.ktor.gateway.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class GatewayAfterForwardTest : FunSpec({
  test("after hooks shape the response in registration order and share call state with before hooks") {
    val marker = AttributeKey<String>("order-marker")
    gatewayHookTest(configure = {
      beforeForward("/api/orders/**") { call.attributes.put(marker, "verified") }
      afterForward("/api/orders/**") {
        yield()
        call.attributes[marker] shouldBe "verified"
        uri shouldBe "/api/orders/42"
        method shouldBe HttpMethod.Post
        response.status shouldBe HttpStatusCode.OK
        response.headers["X-Order-Reply"] shouldBe "initial"
        response.status = HttpStatusCode.Accepted
        response.headers["X-Order-Reply"] = "custom"
        response.headers[HttpHeaders.SetCookie] = "upstream-cookie"
        response.headers[HttpHeaders.ContentLength] = "999"
        response.textBody("First")
      }
      afterForward("/api/orders/**") { response.textBody(response.body.readBytes(maxBytes = 256).decodeToString() + " second") }
    }) { fixture ->
      val result = client.post("/api/orders/42") { hookBrowserRequest() }
      result.status shouldBe HttpStatusCode.Accepted
      result.bodyAsText() shouldBe "First second"
      result.headers["X-Order-Reply"] shouldBe "custom"
      result.headers[HttpHeaders.SetCookie] shouldBe null
      result.headers[HttpHeaders.ContentLength] shouldBe "12"
      fixture.upstreamCalls.get() shouldBe 1
    }
  }

  test("replacing a body removes stale encoding and validators and recalculates the byte length") {
    gatewayHookTest(configure = {
      afterForward("/api/orders/**") {
        response.headers[HttpHeaders.ContentEncoding] shouldBe "gzip"
        response.textBody("café")
      }
    }) { _ ->
      val result = client.get("/api/orders/encoded") { hookBrowserRequest() }
      result.bodyAsText() shouldBe "café"
      result.headers[HttpHeaders.ContentLength] shouldBe "5"
      for (name in listOf(HttpHeaders.ContentEncoding, HttpHeaders.ETag, HttpHeaders.LastModified)) {
        result.headers[name] shouldBe null
      }
    }
  }

  test("local responses and rejections stop subsequent after hooks after a single upstream call") {
    val remaining = AtomicInteger()
    gatewayHookTest(configure = {
      afterForward("/api/orders/**") {
        if (uri.endsWith("/blocked")) reject(HttpStatusCode.Conflict, "Order changed")
        call.respondText("Custom result", status = HttpStatusCode.Accepted)
      }
      afterForward("/api/orders/**") { remaining.incrementAndGet() }
    }) { fixture ->
      client.get("/api/orders/blocked") { hookBrowserRequest() }.status shouldBe HttpStatusCode.Conflict
      val result = client.get("/api/orders/42") { hookBrowserRequest() }
      result.status shouldBe HttpStatusCode.Accepted
      result.bodyAsText() shouldBe "Custom result"
      remaining.get() shouldBe 0
      fixture.upstreamCalls.get() shouldBe 2
    }
  }

  test("explicit byte edits remove upstream representation metadata when installed as the new body") {
    gatewayHookTest(configure = {
      afterForward("/api/orders/**") {
        val bytes = response.body.readBytes(maxBytes = 256)
        bytes.fill('*'.code.toByte())
        response.bytesBody(bytes)
      }
    }) { _ ->
      val result = client.get("/api/orders/encoded") { hookBrowserRequest() }
      val body = result.bodyAsText()
      body.isNotEmpty() shouldBe true
      body.all { it == '*' } shouldBe true
      result.headers[HttpHeaders.ContentEncoding] shouldBe null
      result.headers[HttpHeaders.ETag] shouldBe null
      result.headers[HttpHeaders.LastModified] shouldBe null
    }
  }

  test("after hooks see upstream error responses but do not run for blocked or failed forwarding") {
    val after = AtomicInteger()
    gatewayHookTest(configure = {
      beforeForward("/api/orders/**") {
        if (uri.endsWith("/blocked")) reject(HttpStatusCode.Forbidden, "Locked")
        if (uri.endsWith("/local")) call.respondText("Local response")
      }
      afterForward("/api/orders/**") {
        response.status shouldBe HttpStatusCode.Forbidden
        after.incrementAndGet()
      }
    }) { fixture ->
      client.get("/api/orders/denied") { hookBrowserRequest() }.status shouldBe HttpStatusCode.Forbidden
      client.get("/api/orders/blocked") { hookBrowserRequest() }.status shouldBe HttpStatusCode.Forbidden
      client.get("/api/orders/local") { hookBrowserRequest() }.bodyAsText() shouldBe "Local response"
      client.get("/api/orders/unauthorized") { hookBrowserRequest() }.status shouldBe HttpStatusCode.Unauthorized
      client.get("/api/orders/redirect") { hookBrowserRequest() }.status shouldBe HttpStatusCode.BadGateway
      client.get("/api/orders/oversized") { hookBrowserRequest() }.status shouldBe HttpStatusCode.BadGateway
      after.get() shouldBe 1
      fixture.invalidations.get() shouldBe 1
    }
  }

  test("timeouts skip response hooks") {
    val after = AtomicInteger()
    gatewayHookTest(
      routes = { upstream ->
        gatewayRoutes {
          service("orders", upstream) {
            route("/api/orders/**") {
              upstreamPath = "/orders"
              timeout = 50.milliseconds
            }
          }
        }
      },
      configure = { afterForward("/api/orders/**") { after.incrementAndGet() } }
    ) { _ ->
      client.get("/api/orders/slow") { hookBrowserRequest() }.status shouldBe HttpStatusCode.GatewayTimeout
      after.get() shouldBe 0
    }
  }

  test("transformed bodies retain limits and after hooks belong only to the selected binding") {
    val after = AtomicInteger()
    gatewayHookTest(configure = {
      afterForward("/api/orders/**") {
        after.incrementAndGet()
        response.textBody("x".repeat(257))
      }
    }) { _ ->
      client.get("/api/orders/42") { hookBrowserRequest() }.status shouldBe HttpStatusCode.BadGateway
      client.get("/api/orders/archive/42") { hookBrowserRequest() }.status shouldBe HttpStatusCode.OK
      client.get("/plain/42") { hookBrowserRequest() }.status shouldBe HttpStatusCode.OK
      after.get() shouldBe 1
    }
  }

  test("observing or editing headers preserves HEAD conditional and no-content response semantics") {
    gatewayHookTest(configure = {
      afterForward("/api/orders/**") { response.headers["X-Order-Reply"] = "observed" }
    }) { _ ->
      val head = client.head("/api/orders/metadata") { hookBrowserRequest() }
      head.status shouldBe HttpStatusCode.OK
      head.headers[HttpHeaders.ContentLength] shouldBe "4096"
      head.headers["X-Order-Reply"] shouldBe "observed"
      head.bodyAsText() shouldBe ""
      // Streaming execution avoids Ktor's eager body-size check for 304 representation metadata.
      client.prepareGet("/api/orders/conditional") { hookBrowserRequest() }.execute { conditional ->
        conditional.status shouldBe HttpStatusCode.NotModified
        conditional.headers[HttpHeaders.ContentLength] shouldBe "4096"
      }
      val empty = client.get("/api/orders/empty") { hookBrowserRequest() }
      empty.status shouldBe HttpStatusCode.NoContent
      empty.bodyAsText() shouldBe ""
    }
  }

  test("status changes suppress forbidden bodies and reject unsupported final statuses") {
    gatewayHookTest(configure = {
      afterForward("/api/orders/**") {
        response.textBody("representation")
        response.status = HttpStatusCode.fromValue(uri.substringAfterLast('/').toInt())
      }
    }) { _ ->
      for (status in listOf(HttpStatusCode.NoContent, HttpStatusCode.ResetContent)) {
        val result = client.get("/api/orders/${status.value}") { hookBrowserRequest() }
        result.status shouldBe status
        result.bodyAsText() shouldBe ""
      }
      client.prepareGet("/api/orders/304") { hookBrowserRequest() }.execute { result ->
        result.status shouldBe HttpStatusCode.NotModified
        result.headers[HttpHeaders.ContentLength] shouldBe "14"
      }
      client.get("/api/orders/302") { hookBrowserRequest() }.status shouldBe HttpStatusCode.BadGateway
      client.get("/api/orders/103") { hookBrowserRequest() }.status shouldBe HttpStatusCode.BadGateway
    }
  }

  test("after hooks attach to JSON bindings and reject unknown paths during setup") {
    val hooks = GatewayHooks().apply { afterForward("/unknown/**") {} }
    shouldThrow<IllegalArgumentException> { hooks.bind(hookRoutes("http://127.0.0.1:1")) }
    gatewayHookTest(
      routes = ::hookJsonRoutes,
      configure = { afterForward("/api/orders/**") { response.textBody("JSON route intercepted") } }
    ) { _ ->
      client.get("/api/orders/42") { hookBrowserRequest() }.bodyAsText() shouldBe "JSON route intercepted"
    }
  }
})
