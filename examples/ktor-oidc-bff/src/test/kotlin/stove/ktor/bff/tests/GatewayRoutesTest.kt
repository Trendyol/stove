package stove.ktor.bff.tests

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.http.*
import stove.ktor.bff.gateway.BffGatewayConfiguration
import stove.ktor.gateway.*
import kotlin.time.Duration.Companion.milliseconds

class GatewayRoutesTest : FunSpec({
  test("JSON streaming policies compile through the same DSL") {
    val routes = GatewayConfiguration.parse(
      """{"services":[{"name":"stream","upstream":"https://api.example","routes":[
      {"path":"/events/**","transport":"sse","sse":{"idleTimeoutMillis":1234}},
      {"path":"/socket/**","transport":"websocket","websocket":{"maxFrameBytes":1024,"maxMessageBytes":4096,
       "subprotocols":["orders.v1"],"requireSubprotocol":true}}
    ]}]}"""
    )
    routes.resolve("/events").methods shouldBe setOf(HttpMethod.Get)
    routes.resolve("/events").transport shouldBe GatewayTransport.Sse(1234.milliseconds)
    val socket = routes.resolve("/socket").transport as GatewayTransport.WebSocket
    socket.maxFrameBytes shouldBe 1024
    socket.maxMessageBytes shouldBe 4096
    socket.subprotocols shouldBe setOf("orders.v1")
    socket.requireSubprotocol shouldBe true
  }

  test("invalid and conflicting streaming policies fail during configuration") {
    for (options in listOf(
      "\"transport\":\"unknown\"",
      "\"sse\":{}",
      "\"transport\":\"sse\",\"websocket\":{}",
      "\"transport\":\"sse\",\"methods\":[\"POST\"]",
      "\"transport\":\"sse\",\"sse\":{\"idleTimeoutMillis\":0}",
      "\"transport\":\"websocket\",\"websocket\":{\"maxFrameBytes\":0}",
      "\"transport\":\"websocket\",\"websocket\":{\"requireSubprotocol\":true}"
    )) {
      shouldThrow<RuntimeException> {
        GatewayConfiguration.parse("""{"services":[{"name":"stream","upstream":"https://api.example","routes":[{"path":"/a/**",$options}]}]}""")
      }
    }
  }

  test("service origins and base paths remain fixed while encoded suffixes and repeated query values survive") {
    val routes = gatewayRoutes {
      service("orders", "https://orders.example/v2") {
        scopes("orders:read")
        route("/purchases/**") { upstreamPath = "/orders" }
      }
      service("catalog", "https://catalog.example") {
        scopes("catalog:read")
        route("/catalog/**") { upstreamPath = "/inventory" }
      }
    }
    val orders = routes.bindings.first()
    orders.target("/purchases").toString() shouldBe "https://orders.example/v2/orders"
    orders.target("/purchases/").toString() shouldBe "https://orders.example/v2/orders/"
    orders.target("/purchases/caf%C3%A9/items?q=a%20b&q=c%26d").toString() shouldBe
      "https://orders.example/v2/orders/caf%C3%A9/items?q=a%20b&q=c%26d"
    routes.bindings.last().target("/catalog/widget").toString() shouldBe "https://catalog.example/inventory/widget"
    routes.scopes shouldBe setOf("orders:read", "catalog:read")
    shouldThrow<GatewayFailure> { orders.target("/purchases-other/42") }.status shouldBe HttpStatusCode.BadRequest
  }

  test("ambiguous and traversing request paths cannot escape a binding") {
    val binding = gatewayRoutes { service("orders", "https://orders.example") { route("/orders/**") } }.bindings.single()
    for (suffix in listOf("/../admin", "/%2e%2e/admin", "/%2Fadmin", "/%5Cadmin", "/%252e%252e/admin", "//admin", "/%00", "/%xx", "/item#fragment")) {
      shouldThrow<GatewayFailure> { binding.target("/orders$suffix") }.status shouldBe HttpStatusCode.BadRequest
    }
  }

  test("configuration rejects ambiguous route definitions and non-HTTP upstreams") {
    shouldThrow<IllegalArgumentException> {
      gatewayRoutes {
        service("orders", "https://one.example") { route("/orders/**") }
        service("another", "https://two.example") { route("/orders/**") }
      }
    }
    for (upstream in listOf("file:///tmp/orders", "https://user:password@api.example", "https://api.example?q=x", "https://api.example/#fragment")) {
      shouldThrow<IllegalArgumentException> { gatewayRoutes { service("orders", upstream) { route("/orders/**") } } }
    }
    for (path in listOf("/**", "/orders/*", "/orders/../admin/**", "/orders//items/**")) {
      shouldThrow<IllegalArgumentException> { gatewayRoutes { service("orders", "https://api.example") { route(path) } } }
    }
  }

  test("gateway bindings cannot shadow authentication or application endpoints") {
    for (path in listOf("/auth/**", "/auth/callback/**", "/api/**", "/api/session/**", "/health/**")) {
      val routes = gatewayRoutes { service("orders", "https://api.example") { route(path) } }
      shouldThrow<IllegalArgumentException> {
        BffGatewayConfiguration(routes)
      }
    }
  }

  test("JSON config compiles through the DSL with environment substitution and explicit policies") {
    val routes = GatewayConfiguration.parse(
      """{
        "services": [{
          "name": "orders", "upstream": "${'$'}{ORDERS_URL}", "scopes": ["orders:read"],
          "routes": [{"path": "/purchases/**", "upstreamPath": "/orders", "methods": ["GET", "POST"],
            "timeoutMillis": 750, "requestLimitBytes": 512, "responseLimitBytes": 1024,
            "requestHeaders": ["Idempotency-Key"], "responseHeaders": ["ETag"]}]
        }]
      }""",
      mapOf("ORDERS_URL" to "https://api.example/v2")
    )
    val binding = routes.bindings.single()
    binding.target("/purchases/42").toString() shouldBe "https://api.example/v2/orders/42"
    binding.methods shouldBe setOf(HttpMethod.Get, HttpMethod.Post)
    binding.timeout shouldBe 750.milliseconds
    binding.requestLimitBytes shouldBe 512
    binding.responseLimitBytes shouldBe 1024
    routes.scopes shouldBe setOf("orders:read")
  }

  test("configuration mistakes fail during startup") {
    val missingEnvironment = """{"services":[{"name":"orders","upstream":"${'$'}{ORDERS_URL}","routes":[{"path":"/orders/**"}]}]}"""
    shouldThrow<IllegalStateException> { GatewayConfiguration.parse(missingEnvironment) }
    shouldThrow<IllegalArgumentException> { GatewayConfiguration.parse("""{"services":[],"servcies":[]}""") }
    shouldThrow<IllegalArgumentException> {
      GatewayConfiguration.parse(missingEnvironment.replace("\"path\":\"/orders/**\"", "\"path\":\"/orders/**\",\"timeoutMs\":500"), mapOf("ORDERS_URL" to "https://api.example"))
    }
  }

  test("header policies omit credentials and connection-nominated fields") {
    val policy = GatewayHeaders(GatewayHeaders.DefaultRequest + "X-Request-Id", GatewayHeaders.DefaultResponse)
    val source = headersOf(
      "Authorization" to listOf("Bearer browser-token"),
      "Cookie" to listOf("session=browser"),
      "Connection" to listOf("X-Request-Id, ETag"),
      "X-Request-Id" to listOf("hop-only"),
      "Idempotency-Key" to listOf("item-42"),
      "ETag" to listOf("v1"),
      "Set-Cookie" to listOf("session=upstream"),
      "Content-Type" to listOf("application/json")
    )
    policy.request(source).names() shouldBe setOf("Idempotency-Key", "Content-Type")
    policy.response(source).names() shouldBe setOf("Content-Type")
    for (header in listOf("Authorization", "DPoP", "Cookie", "Set-Cookie", "Host", "Content-Length", "Location", "X-CSRF-Token")) {
      shouldThrow<IllegalArgumentException> { GatewayHeaders(setOf(header), emptySet()) }
      shouldThrow<IllegalArgumentException> { GatewayHeaders(emptySet(), setOf(header)) }
    }
  }
})
