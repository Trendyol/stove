package stove.ktor.bff.gateway

import io.ktor.http.HttpMethod
import stove.ktor.gateway.*

/** Convenient local default. Deployments can replace the entire table with BFF_ROUTES_FILE. */
internal fun defaultRoutes(resourceApi: String): GatewayRoutes {
  if (resourceApi.isBlank()) return GatewayRoutes.Empty
  return gatewayRoutes {
    service("orders", resourceApi) {
      scopes("orders:read")
      route("/api/order-events/**") {
        upstreamPath = "/order-events"
        sse()
      }
      route("/api/order-socket/**") {
        upstreamPath = "/order-socket"
        webSocket {
          subprotocols = setOf("orders.v1")
          requireSubprotocol = true
        }
      }
      for (path in listOf("/orders/**", "/api/orders/**")) {
        route(path) {
          upstreamPath = "/orders"
          methods = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete)
        }
      }
    }
  }
}
