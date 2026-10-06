@file:OptIn(io.ktor.utils.io.InternalAPI::class)

package stove.ktor.gateway.internal

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.ClientWebSocketSession
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.*
import io.ktor.server.websocket.WebSocketUpgrade
import kotlinx.coroutines.*
import stove.ktor.gateway.*

/** Holds the upstream response open for the complete browser upgrade and relay. */
internal class GatewayWebSocketExchange(
  private val http: HttpClient,
  private val call: ApplicationCall,
  private val binding: GatewayBinding,
  private val outgoing: GatewayRequest,
  private val access: GatewayAccess,
  private val intercept: GatewayResponseInterceptor
) {
  private val policy = binding.transport as GatewayTransport.WebSocket
  private val offered = requestedProtocols(call.request.headers).filter { it in policy.subprotocols }
  private val target = URLBuilder(binding.target(outgoing.uri)).apply {
    protocol = if (protocol == URLProtocol.HTTPS) URLProtocol.WSS else URLProtocol.WS
  }.build()

  suspend fun execute(): Unit = supervisorScope {
    val revoked = async { access.awaitRevocation() }
    try {
      opening(binding.timeout) { opened ->
        request().execute { upstream ->
          opened()
          access.validate(upstream)
          if (revoked.isCompleted) throw GatewayFailure(HttpStatusCode.Unauthorized, "Gateway authorization ended")
          if (upstream.status == HttpStatusCode.SwitchingProtocols) {
            upgrade(upstream, revoked)
          } else {
            val completed = withTimeoutOrNull(binding.timeout) {
              rejectUpgrade(upstream)
              true
            }
            if (completed != true) throw GatewayFailure(HttpStatusCode.GatewayTimeout, "Upstream rejection timed out")
          }
        }
      }
    } finally {
      revoked.cancel()
    }
  }

  private suspend fun request() = http.prepareRequest(target) {
    method = HttpMethod.Get
    headers.appendAll(binding.headers.request(outgoing.headers))
    if (offered.isNotEmpty()) headers[HttpHeaders.SecWebSocketProtocol] = offered.joinToString(", ")
    timeout {
      requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
      connectTimeoutMillis = binding.timeout.inWholeMilliseconds
    }
    access.prepare(this)
  }

  private suspend fun upgrade(upstream: HttpResponse, revoked: Deferred<Unit>) {
    val protocol = selectedProtocol(upstream.headers, offered, policy)
    verifyUpgrade(upstream)
    val session = upstream.body<ClientWebSocketSession>()
    session.maxFrameSize = policy.maxFrameBytes.toLong()
    try {
      val body = GatewayBody.bytes(byteArrayOf(), binding.responseLimitBytes, HttpStatusCode.BadGateway)
      val response = GatewayResponse(upstream.status, binding.headers.response(upstream.headers), body, HttpMethod.Get, null)
      intercept(response) { prepared ->
        prepared.headers.forEach { name, values -> values.forEach { call.response.header(name, it) } }
        relay(session, protocol, revoked)
      }
    } finally {
      session.cancel()
    }
  }

  private suspend fun relay(session: ClientWebSocketSession, protocol: String?, revoked: Deferred<Unit>): Unit = coroutineScope {
    // The relay belongs to this exchange even when an engine uses a separate upgrade handler job.
    val exchange = this
    call.respond(
      WebSocketUpgrade(call, protocol, installExtensions = false) {
        val downstream = this
        val relay = exchange.async { GatewayWebSocketRelay(policy).relay(downstream, session, revoked) }
        try {
          relay.await()
        } finally {
          relay.cancel()
        }
      }
    )
  }

  private suspend fun rejectUpgrade(upstream: HttpResponse) {
    if (upstream.status.value !in 400..599) throw GatewayFailure(HttpStatusCode.BadGateway, "Upstream refused the WebSocket upgrade")
    // The WebSockets plugin's body transformation expects 101. Rejected handshakes carry ordinary HTTP bytes.
    val body = GatewayBody.stream(upstream.rawContent, upstream.contentLength(), binding.responseLimitBytes, HttpStatusCode.BadGateway)
    try {
      body.checkLength()
      val response = GatewayResponse(upstream.status, binding.headers.response(upstream.headers), body, HttpMethod.Get, body.contentLength)
      intercept(response) { call.respondGateway(it) }
    } finally {
      body.cancel()
    }
  }
}
