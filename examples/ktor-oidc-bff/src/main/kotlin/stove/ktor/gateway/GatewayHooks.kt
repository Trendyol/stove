package stove.ktor.gateway

import io.ktor.http.*
import io.ktor.server.application.ApplicationCall

/** Business logic runs after browser policies and known-length checks, before credentials or upstream I/O. */
@GatewayDsl
class GatewayForwardContext internal constructor(
  val call: ApplicationCall,
  val uri: String,
  val method: HttpMethod,
  val request: GatewayRequestBuilder
) {
  fun reject(status: HttpStatusCode, message: String): Nothing = throw GatewayFailure(status, message)
}

/** Runs when upstream headers arrive, before streaming the response to the browser. */
@GatewayDsl
class GatewayAfterForwardContext internal constructor(
  val call: ApplicationCall,
  val uri: String,
  val method: HttpMethod,
  val response: GatewayResponseBuilder
) {
  fun reject(status: HttpStatusCode, message: String): Nothing = throw GatewayFailure(status, message)
}

/** Attach compiled application logic to existing bindings, including bindings loaded from JSON. */
@GatewayDsl
class GatewayHooks {
  private val before = linkedMapOf<String, MutableList<suspend GatewayForwardContext.() -> Unit>>()
  private val after = linkedMapOf<String, MutableList<suspend GatewayAfterForwardContext.() -> Unit>>()

  fun beforeForward(path: String, handler: suspend GatewayForwardContext.() -> Unit) {
    before.getOrPut(path) { mutableListOf() }.add(handler)
  }

  fun afterForward(path: String, handler: suspend GatewayAfterForwardContext.() -> Unit) {
    after.getOrPut(path) { mutableListOf() }.add(handler)
  }

  internal fun bind(routes: GatewayRoutes): Map<String, BoundGatewayHooks> {
    val paths = routes.bindings.map { "${it.prefix}/**" }.toSet()
    val configured = before.keys + after.keys
    require(configured.all { it in paths }) { "Gateway hooks reference unconfigured bindings: ${configured - paths}" }
    return routes.bindings.associate { binding ->
      val path = "${binding.prefix}/**"
      binding.prefix to BoundGatewayHooks(
        binding.beforeForward + before[path].orEmpty(),
        binding.afterForward + after[path].orEmpty()
      )
    }
  }
}

internal class BoundGatewayHooks(
  val before: List<suspend GatewayForwardContext.() -> Unit>,
  val after: List<suspend GatewayAfterForwardContext.() -> Unit>
)
