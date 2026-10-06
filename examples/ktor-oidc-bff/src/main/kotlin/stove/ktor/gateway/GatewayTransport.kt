package stove.ktor.gateway

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface GatewayTransport {
  data object Http : GatewayTransport
  data class Sse(val idleTimeout: Duration) : GatewayTransport
  data class WebSocket(
    val idleTimeout: Duration,
    val closeTimeout: Duration,
    val maxFrameBytes: Int,
    val maxMessageBytes: Int,
    val subprotocols: Set<String>,
    val requireSubprotocol: Boolean
  ) : GatewayTransport
}

@GatewayDsl
class GatewaySseBuilder {
  var idleTimeout: Duration = 60.seconds
  internal fun build(): GatewayTransport.Sse {
    validateIdleTimeout(idleTimeout)
    return GatewayTransport.Sse(idleTimeout)
  }
}

@GatewayDsl
class GatewayWebSocketBuilder {
  var idleTimeout: Duration = 60.seconds
  var closeTimeout: Duration = 5.seconds
  var maxFrameBytes: Int = 64 * 1024
  var maxMessageBytes: Int = 1024 * 1024
  var subprotocols: Set<String> = emptySet()
  var requireSubprotocol: Boolean = false

  internal fun build(): GatewayTransport.WebSocket {
    validateIdleTimeout(idleTimeout)
    require(closeTimeout.inWholeMilliseconds in 1..30_000) { "WebSocket close timeout must be between 1ms and 30s" }
    require(maxFrameBytes in 1..1024 * 1024) { "WebSocket frames must be limited to 1 byte through 1 MiB" }
    require(maxMessageBytes in maxFrameBytes..16 * 1024 * 1024) { "WebSocket messages must allow a frame and be limited to 16 MiB" }
    require(subprotocols.all(::isProtocolToken)) { "WebSocket subprotocols must be valid HTTP tokens" }
    require(!requireSubprotocol || subprotocols.isNotEmpty()) { "A required WebSocket subprotocol needs an allowlist" }
    return GatewayTransport.WebSocket(idleTimeout, closeTimeout, maxFrameBytes, maxMessageBytes, subprotocols.toSet(), requireSubprotocol)
  }
}

internal fun isProtocolToken(value: String): Boolean = value.isNotEmpty() && value.all {
  (it.isLetterOrDigit() && it.code < 128) || it in "!#$%&'*+-.^_`|~"
}

private fun validateIdleTimeout(value: Duration) {
  require(value.inWholeMilliseconds in 1..3_600_000) { "Stream idle timeout must be between 1ms and 1h" }
}
