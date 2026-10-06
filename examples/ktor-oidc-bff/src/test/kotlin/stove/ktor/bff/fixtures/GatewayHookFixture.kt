package stove.ktor.bff.fixtures

import com.trendyol.stove.system.PortFinder
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import stove.ktor.bff.gateway.OidcGatewayAccess
import stove.ktor.bff.gateway.oidc
import stove.ktor.bff.resourceapi.gatewayHookApi
import stove.ktor.gateway.*
import stove.ktor.oidc.client.TokenBinding
import stove.ktor.oidc.server.SessionCsrf
import java.util.concurrent.atomic.AtomicInteger

/** Real HTTP transport; synthetic identities isolate interception from the provider E2E scenarios. */
class GatewayHookFixture : AutoCloseable {
  val credentials = AtomicInteger()
  val upstreamCalls = AtomicInteger()
  val invalidations = AtomicInteger()
  private val port = PortFinder.findAvailablePort()
  val origin = "http://127.0.0.1:$port"
  private val upstream = embeddedServer(CIO, host = "127.0.0.1", port = port) {
    gatewayHookApi { upstreamCalls.incrementAndGet() }
  }.start(wait = false)

  fun install(application: Application, routes: GatewayRoutes, configure: GatewayHooks.() -> Unit) = with(application) {
    install(Authentication) {
      bearer("browser") { authenticate { if (it.token == "browser-token") HookBrowser("alice") else null } }
    }
    install(Gateway) {
      this.routes = routes
      hooks = GatewayHooks().apply(configure)
      oidc {
        credentials.incrementAndGet()
        OidcGatewayAccess(TokenBinding.Bearer, { "upstream-token" }, { invalidations.incrementAndGet() })
      }
    }
    install(StatusPages) {
      exception<GatewayFailure> { call, failure -> call.respondText(failure.message.orEmpty(), status = failure.status) }
    }
    routing {
      authenticate("browser") {
        install(SessionCsrf) {
          trustedOrigin = "https://browser.example"
          token = { "csrf-token" }
        }
        gateway()
      }
    }
  }

  override fun close() = upstream.stop(0, 1_000)
}

data class HookBrowser(val subject: String)

fun HttpRequestBuilder.hookBrowserRequest() {
  bearerAuth("browser-token")
  header(HttpHeaders.Origin, "https://browser.example")
  header("X-CSRF-Token", "csrf-token")
}

suspend fun gatewayHookTest(
  configure: GatewayHooks.() -> Unit = {},
  routes: (String) -> GatewayRoutes = ::hookRoutes,
  assertions: suspend ApplicationTestBuilder.(GatewayHookFixture) -> Unit
) {
  GatewayHookFixture().use { fixture ->
    testApplication {
      application { fixture.install(this, routes(fixture.origin), configure) }
      assertions(fixture)
    }
  }
}

fun hookRoutes(upstream: String): GatewayRoutes = gatewayRoutes {
  service("orders", upstream) {
    route("/api/orders/**") {
      upstreamPath = "/orders"
      methods = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Post)
      requestLimitBytes = 32
      responseLimitBytes = 256
      requestHeaders += "X-Order-Channel"
      responseHeaders += "X-Order-Reply"
      beforeForward { request.headers["X-Order-Channel"] = "web" }
      afterForward { response.headers["X-Order-Reply"] = "initial" }
    }
    route("/api/orders/archive/**") { upstreamPath = "/archive" }
    route("/plain/**") { upstreamPath = "/orders" }
  }
}

fun hookJsonRoutes(upstream: String): GatewayRoutes = GatewayConfiguration.parse(
  """
  {
    "services": [{
      "name": "orders",
      "upstream": "$upstream",
      "routes": [{ "path": "/api/orders/**" }]
    }]
  }
  """.trimIndent()
)
