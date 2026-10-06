@file:OptIn(io.ktor.utils.io.InternalAPI::class)

package stove.ktor.gateway.internal

import io.ktor.client.request.ClientUpgradeContent
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import stove.ktor.gateway.*
import java.util.Base64

internal class GatewayWebSocketHandshake(private val allowedOrigins: Set<String>, private val allowMissingOrigin: Boolean) {
  fun validate(call: ApplicationCall, policy: GatewayTransport.WebSocket) {
    val headers = call.request.headers
    val origins = headers.getAll(HttpHeaders.Origin).orEmpty()
    val validOrigin = if (origins.isEmpty()) allowMissingOrigin else origins.size == 1 && origins.single() in allowedOrigins
    if (!validOrigin) throw GatewayFailure(HttpStatusCode.Forbidden, "WebSocket origin is not allowed")
    if (headers.tokens(HttpHeaders.Upgrade).map(String::lowercase) != listOf("websocket") ||
      "upgrade" !in headers.tokens(HttpHeaders.Connection).map(String::lowercase)
    ) {
      throw GatewayFailure(HttpStatusCode.BadRequest, "Expected a WebSocket upgrade")
    }
    if (headers.getAll(HttpHeaders.SecWebSocketVersion) != listOf("13")) {
      call.response.header(HttpHeaders.SecWebSocketVersion, "13")
      throw GatewayFailure(HttpStatusCode.UpgradeRequired, "WebSocket version 13 is required")
    }
    val keys = headers.getAll(HttpHeaders.SecWebSocketKey).orEmpty()
    val validKey = keys.size == 1 && validWebSocketKey(keys.single())
    if (!validKey) throw GatewayFailure(HttpStatusCode.BadRequest, "Invalid WebSocket key")
    val protocols = requestedProtocols(headers)
    if (policy.requireSubprotocol && protocols.none { it in policy.subprotocols }) {
      throw GatewayFailure(HttpStatusCode.BadRequest, "A supported WebSocket subprotocol is required")
    }
  }
}

internal fun verifyUpgrade(response: HttpResponse) {
  if (response.headers.tokens(HttpHeaders.Upgrade).map(String::lowercase) != listOf("websocket") ||
    "upgrade" !in response.headers.tokens(HttpHeaders.Connection).map(String::lowercase) ||
    response.headers.getAll(HttpHeaders.SecWebSocketAccept).orEmpty().size != 1
  ) {
    throw GatewayFailure(HttpStatusCode.BadGateway, "Invalid upstream WebSocket handshake")
  }
  try {
    (response.request.content as ClientUpgradeContent).verify(response.headers)
  } catch (error: IllegalStateException) {
    throw GatewayFailure(HttpStatusCode.BadGateway, "Invalid upstream WebSocket accept key", error)
  }
}

internal fun selectedProtocol(headers: Headers, offered: List<String>, policy: GatewayTransport.WebSocket): String? {
  if (headers.getAll(HttpHeaders.SecWebSocketExtensions).orEmpty().isNotEmpty()) {
    throw GatewayFailure(HttpStatusCode.BadGateway, "WebSocket extensions are not enabled")
  }
  val protocols = headers.tokens(HttpHeaders.SecWebSocketProtocol)
  if (protocols.size > 1 || protocols.any { it !in offered } || (policy.requireSubprotocol && protocols.isEmpty())) {
    throw GatewayFailure(HttpStatusCode.BadGateway, "Upstream selected an invalid WebSocket subprotocol")
  }
  return protocols.singleOrNull()
}

internal fun requestedProtocols(headers: Headers): List<String> = headers.tokens(HttpHeaders.SecWebSocketProtocol).also {
  if (it.any { protocol -> !isProtocolToken(protocol) } || it.distinct().size != it.size) {
    throw GatewayFailure(HttpStatusCode.BadRequest, "Invalid WebSocket subprotocols")
  }
}

private fun Headers.tokens(name: String): List<String> = getAll(name).orEmpty().flatMap { it.split(',') }.map(String::trim)

private fun validWebSocketKey(value: String): Boolean = try {
  val bytes = Base64.getDecoder().decode(value)
  bytes.size == 16 && Base64.getEncoder().encodeToString(bytes) == value
} catch (_: IllegalArgumentException) {
  false
}
