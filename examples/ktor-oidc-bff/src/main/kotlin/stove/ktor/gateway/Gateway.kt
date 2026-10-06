package stove.ktor.gateway

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.server.application.*
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.routing.*
import io.ktor.server.websocket.WebSockets
import io.ktor.util.AttributeKey
import stove.ktor.gateway.internal.GatewayRuntime
import java.net.URI

class GatewayConfig {
  var routes: GatewayRoutes = GatewayRoutes.Empty
  var hooks: GatewayHooks = GatewayHooks()
  var access: (ApplicationCall) -> GatewayAccess = { GatewayAccess.Anonymous }
  var webSocketOrigins: Set<String> = emptySet()
  var allowMissingWebSocketOrigin: Boolean = false

  /** The gateway owns the returned client, applies transport policies, and closes it during shutdown. */
  var clientFactory: (HttpClientConfig<*>.() -> Unit) -> HttpClient = { HttpClient(CIO, it) }
  private val clientConfiguration = mutableListOf<HttpClientConfig<*>.() -> Unit>()

  fun client(configure: HttpClientConfig<*>.() -> Unit) {
    clientConfiguration += configure
  }

  internal fun createClient(required: HttpClientConfig<*>.() -> Unit): HttpClient = clientFactory {
    clientConfiguration.forEach { it() }
    required()
  }
}

/** Reusable transports and policies. Authentication and application routes belong to the host. */
val Gateway = createApplicationPlugin("Gateway", ::GatewayConfig) {
  val sockets = pluginConfig.routes.bindings.map { it.transport }.filterIsInstance<GatewayTransport.WebSocket>()
  if (sockets.isNotEmpty()) {
    require(pluginConfig.webSocketOrigins.isNotEmpty() || pluginConfig.allowMissingWebSocketOrigin) {
      "WebSocket routes require allowed browser origins or explicit access for clients without Origin"
    }
    pluginConfig.webSocketOrigins.forEach { origin ->
      val uri = URI(origin)
      require(
        uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null && uri.query == null &&
          uri.fragment == null && uri.path.isEmpty()
      ) { "Expected an exact HTTP(S) WebSocket origin: $origin" }
    }
    val maximum = sockets.maxOf { it.maxFrameBytes }.toLong()
    val installed = application.pluginOrNull(WebSockets)
    if (installed == null) {
      application.install(WebSockets) {
        maxFrameSize = maximum
        channels {
          incoming = bounded(8)
          outgoing = bounded(8)
        }
      }
    } else {
      require(
        installed.maxFrameSize <= maximum && installed.channelsConfig.incoming.capacity in 1..8 &&
          installed.channelsConfig.outgoing.capacity in 1..8
      ) {
        "Existing WebSockets plugin must use bounded frame sizes and queues (at most 8 frames)"
      }
    }
  }
  val runtime = GatewayRuntime(pluginConfig)
  application.attributes.put(RuntimeKey, runtime)
  on(MonitoringEvent(ApplicationStopPreparing)) { runtime.close() }
}

/** Mount inside the host application's authentication and browser security policies. */
fun Route.gateway() {
  val runtime = application.attributes[RuntimeKey]
  runtime.routes.bindings.forEach { binding ->
    route("${binding.prefix}/{remaining...}") { handle { runtime.forward(call, binding) } }
  }
}

internal val RuntimeKey = AttributeKey<GatewayRuntime>("GatewayRuntime")
