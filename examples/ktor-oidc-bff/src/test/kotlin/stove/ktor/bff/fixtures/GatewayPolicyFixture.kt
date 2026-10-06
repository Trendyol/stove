package stove.ktor.bff.fixtures

import io.ktor.http.*
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import stove.ktor.gateway.*

/** Rejections must finish before requesting credentials or opening an upstream connection. */
fun Application.gatewayPolicyFixture() {
  install(Gateway) {
    routes = gatewayRoutes {
      service("orders", "http://127.0.0.1:1") {
        route("/orders/**") {
          methods = setOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Patch)
          requestLimitBytes = 16
        }
        route("/orders/archive/**") { upstreamPath = "/archive" }
      }
    }
    access = { error("Rejected requests must not resolve credentials") }
  }
  install(StatusPages) {
    exception<GatewayFailure> { call, failure -> call.respondText(failure.message.orEmpty(), status = failure.status) }
  }
  routing {
    get("/safe-mutation") { call.gatewayRequest("/orders/42", HttpMethod.Patch) { call.respondGateway(it) } }
    post("/method-denied") { call.gatewayRequest("/orders/archive/42", HttpMethod.Post) { call.respondGateway(it) } }
    post("/oversized") {
      call.gatewayRequest("/orders/42", HttpMethod.Post, configure = { textBody("x".repeat(17)) }) { call.respondGateway(it) }
    }
    get("/unknown") { call.gatewayRequest("/unconfigured") { call.respondGateway(it) } }
    get("/traversal") { call.gatewayRequest("/orders/%2e%2e/admin") { call.respondGateway(it) } }
  }
}
