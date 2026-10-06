package stove.ktor.bff.tests

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.auth.principal
import io.ktor.server.response.respondText
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*
import stove.ktor.bff.fixtures.*
import stove.ktor.gateway.*
import java.util.concurrent.atomic.AtomicInteger

class GatewayHooksTest : FunSpec({
  test("hooks run in order and automatically forward modified data with managed credentials") {
    gatewayHookTest(configure = {
      beforeForward("/api/orders/**") {
        yield()
        checkNotNull(call.principal<HookBrowser>()).subject shouldBe "alice"
        uri shouldBe "/api/orders/42?confirmed=true"
        method shouldBe HttpMethod.Post
        request.body.readBytes(maxBytes = 32).decodeToString() shouldBe "original"
        request.headers["X-Order-Channel"] shouldBe "web"
        request.headers["X-Order-Channel"] = "mobile"
        request.headers[HttpHeaders.Cookie] = "do-not-forward"
        request.textBody("enriched")
      }
      beforeForward("/api/orders/**") { request.textBody(request.body.readBytes(maxBytes = 32).decodeToString() + "-again") }
    }) { fixture ->
      val response = client.post("/api/orders/42?confirmed=true") {
        hookBrowserRequest()
        setBody("original")
      }
      response.status shouldBe HttpStatusCode.OK
      Json.parseToJsonElement(response.bodyAsText()).jsonObject shouldBe buildJsonObject {
        put("path", "/orders/42")
        put("method", "POST")
        put("body", "enriched-again")
        put("channel", "mobile")
        put("cookieReceived", false)
      }
      fixture.credentials.get() shouldBe 1
      fixture.upstreamCalls.get() shouldBe 1
    }
  }

  test("a rejection or local Ktor response stops remaining hooks and upstream access") {
    val remaining = AtomicInteger()
    gatewayHookTest(configure = {
      beforeForward("/api/orders/**") {
        if (uri.endsWith("/blocked")) reject(HttpStatusCode.Forbidden, "Order is locked")
        call.respondText("No pending orders", status = HttpStatusCode.Accepted)
      }
      beforeForward("/api/orders/**") { remaining.incrementAndGet() }
    }) { fixture ->
      val rejected = client.get("/api/orders/blocked") { hookBrowserRequest() }
      rejected.status shouldBe HttpStatusCode.Forbidden
      rejected.bodyAsText() shouldBe "Order is locked"
      val local = client.get("/api/orders/empty") { hookBrowserRequest() }
      local.status shouldBe HttpStatusCode.Accepted
      local.bodyAsText() shouldBe "No pending orders"
      remaining.get() shouldBe 0
      fixture.credentials.get() shouldBe 0
      fixture.upstreamCalls.get() shouldBe 0
    }
  }

  test("authentication CSRF method and incoming body policies precede business hooks") {
    val hooks = AtomicInteger()
    gatewayHookTest(configure = { beforeForward("/api/orders/**") { hooks.incrementAndGet() } }) { fixture ->
      client.get("/api/orders/42").status shouldBe HttpStatusCode.Unauthorized
      client.post("/api/orders/42") { bearerAuth("browser-token") }.status shouldBe HttpStatusCode.Forbidden
      client.delete("/api/orders/42") { hookBrowserRequest() }.status shouldBe HttpStatusCode.MethodNotAllowed
      client.post("/api/orders/42") {
        hookBrowserRequest()
        setBody("x".repeat(33))
      }.status shouldBe HttpStatusCode.PayloadTooLarge
      hooks.get() shouldBe 0
      fixture.credentials.get() shouldBe 0
      fixture.upstreamCalls.get() shouldBe 0
    }
  }

  test("body limits are checked again after hooks change the outgoing request") {
    gatewayHookTest(configure = { beforeForward("/api/orders/**") { request.textBody("x".repeat(33)) } }) { fixture ->
      client.post("/api/orders/42") { hookBrowserRequest() }.status shouldBe HttpStatusCode.PayloadTooLarge
      fixture.credentials.get() shouldBe 0
      fixture.upstreamCalls.get() shouldBe 0
    }
  }

  test("hooks belong to the selected binding and each call has independent outgoing state") {
    val hooks = AtomicInteger()
    gatewayHookTest(configure = {
      beforeForward("/api/orders/**") {
        request.headers["X-Order-Channel"] = request.headers["X-Order-Channel"] + "-${hooks.incrementAndGet()}"
      }
    }) { fixture ->
      for (index in 1..2) {
        val response = client.get("/api/orders/$index") { hookBrowserRequest() }
        Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("channel").jsonPrimitive.content shouldBe "web-$index"
      }
      client.get("/api/orders/archive/42") { hookBrowserRequest() }.status shouldBe HttpStatusCode.OK
      client.get("/plain/42") { hookBrowserRequest() }.status shouldBe HttpStatusCode.OK
      hooks.get() shouldBe 2
      fixture.upstreamCalls.get() shouldBe 4
    }
  }

  test("hooks can attach to a JSON binding without redefining its transport configuration") {
    gatewayHookTest(
      routes = ::hookJsonRoutes,
      configure = { beforeForward("/api/orders/**") { reject(HttpStatusCode.Conflict, "Order already submitted") } }
    ) { fixture ->
      client.get("/api/orders/42") { hookBrowserRequest() }.status shouldBe HttpStatusCode.Conflict
      fixture.upstreamCalls.get() shouldBe 0
    }
  }

  test("unconfigured hook paths fail during setup") {
    val hooks = GatewayHooks().apply { beforeForward("/api/typo/**") {} }
    shouldThrow<IllegalArgumentException> { hooks.bind(hookRoutes("http://127.0.0.1:1")) }
  }
})
