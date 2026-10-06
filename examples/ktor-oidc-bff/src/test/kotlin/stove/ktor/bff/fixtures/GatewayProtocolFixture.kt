package stove.ktor.bff.fixtures

import com.trendyol.stove.system.PortFinder
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.ClientWebSocketSession
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import stove.ktor.bff.auth.*
import stove.ktor.bff.gateway.*
import stove.ktor.bff.resourceapi.*
import stove.ktor.gateway.*
import stove.ktor.oidc.client.TokenBinding
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

class GatewayProtocolFixture(
  idle: Duration,
  dpop: Boolean,
  configure: GatewayHooks.() -> Unit
) : AutoCloseable {
  val state = ProtocolApiState()
  val revoked = CompletableDeferred<Unit>()
  private val upstreamPort = PortFinder.findAvailablePort()
  private val upstream = embeddedServer(CIO, host = "127.0.0.1", port = upstreamPort) { gatewayProtocolApi(state) }.start(false)
  private val port = PortFinder.findAvailablePort()
  val origin = "http://127.0.0.1:$port"
  private val gateway = embeddedServer(CIO, host = "127.0.0.1", port = port) {
    install(Gateway) {
      webSocketOrigins = setOf(origin)
      access = {
        object : GatewayAccess {
          override suspend fun awaitRevocation() {
            revoked.await()
          }
        }
      }
      if (dpop) {
        val binding = TokenBinding.Dpop()
        oidc { OidcGatewayAccess(binding, { "access" }, { state.invalidations.incrementAndGet() }, { revoked.await() }) }
      }
      hooks = GatewayHooks().apply(configure)
      routes = gatewayRoutes {
        service("protocols", "http://127.0.0.1:$upstreamPort") {
          route("/events/**") {
            timeout = 250.milliseconds
            responseLimitBytes = 32
            responseHeaders += "X-Hook"
            sse { idleTimeout = idle }
          }
          route("/sockets/**") {
            timeout = 250.milliseconds
            responseHeaders += "X-Hook"
            webSocket {
              idleTimeout = idle
              closeTimeout = 150.milliseconds
              maxFrameBytes = 64
              maxMessageBytes = 96
              subprotocols = setOf("orders.v1")
            }
          }
          route("/bulk/**") {
            upstreamPath = "/sockets"
            webSocket {
              idleTimeout = idle
              closeTimeout = 150.milliseconds
              maxFrameBytes = 64 * 1024
              maxMessageBytes = 64 * 1024
            }
          }
          route("/required/**") {
            upstreamPath = "/sockets"
            webSocket {
              subprotocols = setOf("orders.v1")
              requireSubprotocol = true
            }
          }
        }
      }
    }
    install(StatusPages) {
      exception<GatewayFailure> { call, error ->
        if (call.response.isCommitted) throw error
        call.respondText(error.message.orEmpty(), status = error.status)
      }
    }
    routing { gateway() }
  }.start(false)
  val client = HttpClient(ClientCIO) {
    install(ClientWebSockets) {
      channels {
        incoming = bounded(8)
        outgoing = bounded(8)
      }
    }
    install(HttpTimeout) { requestTimeoutMillis = 5_000 }
  }

  suspend fun socket(path: String = "/sockets/echo", test: suspend ClientWebSocketSession.() -> Unit) {
    client.prepareGet(origin.replace("http:", "ws:") + path) {
      header(HttpHeaders.Origin, origin)
      header(HttpHeaders.SecWebSocketProtocol, "orders.v1")
    }.execute { response ->
      check(response.status == HttpStatusCode.SwitchingProtocols) { "Upgrade failed: ${response.status}" }
      val session = response.body<ClientWebSocketSession>()
      try {
        session.test()
      } finally {
        session.cancel()
      }
    }
  }

  suspend fun handshake(path: String, headers: Map<String, String> = mapOf(HttpHeaders.Origin to origin)): HttpStatusCode =
    client.prepareGet(origin.replace("http:", "ws:") + path) {
      headers.forEach { (name, value) -> header(name, value) }
    }.execute { it.status }

  fun stopGateway() = gateway.stop(0, 1_000)

  override fun close() {
    state.releaseEvents.complete(Unit)
    client.close()
    stopGateway()
    upstream.stop(0, 1_000)
  }
}

suspend fun protocolGatewayTest(
  idle: Duration = 500.milliseconds,
  dpop: Boolean = false,
  configure: GatewayHooks.() -> Unit = {},
  test: suspend GatewayProtocolFixture.() -> Unit
) {
  GatewayProtocolFixture(idle, dpop, configure).use { fixture -> withTimeout(10_000) { fixture.test() } }
}
