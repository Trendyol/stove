package stove.ktor.gateway.internal

import io.ktor.client.HttpClient
import io.ktor.server.application.ApplicationCall
import stove.ktor.gateway.*

internal typealias GatewayResponseInterceptor = suspend (GatewayResponse, suspend (GatewayResponse) -> Unit) -> Unit

/** Keep browser handshake checks separate from a single connection's lifecycle. */
internal class GatewayWebSocketTransport(
  private val http: HttpClient,
  allowedOrigins: Set<String>,
  allowMissingOrigin: Boolean
) {
  private val handshake = GatewayWebSocketHandshake(allowedOrigins, allowMissingOrigin)

  fun validate(call: ApplicationCall, policy: GatewayTransport.WebSocket) = handshake.validate(call, policy)

  suspend fun forward(
    call: ApplicationCall,
    binding: GatewayBinding,
    outgoing: GatewayRequest,
    access: GatewayAccess,
    intercept: GatewayResponseInterceptor
  ) = GatewayWebSocketExchange(http, call, binding, outgoing, access, intercept).execute()
}
