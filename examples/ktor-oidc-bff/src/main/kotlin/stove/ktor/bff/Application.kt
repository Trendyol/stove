package stove.ktor.bff

import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.Route
import kotlinx.coroutines.runBlocking
import stove.ktor.bff.config.BffConfiguration
import stove.ktor.bff.gateway.*
import stove.ktor.bff.orders.orderRoutes
import stove.ktor.gateway.GatewayHooks

fun main(args: Array<String>) {
  startBff(args, wait = true)
}

fun startBff(
  args: Array<String>,
  wait: Boolean = false,
  gateway: GatewayHooks.() -> Unit = {},
  customRoutes: Route.() -> Unit = {}
): Application {
  val configuration = BffConfiguration.load(args)
  val forwarding = BffGatewayConfiguration.load(args)
  // The entry point is synchronous; provider HTTP and storage setup expose suspending APIs.
  val resources = runBlocking { BffResources.open(configuration) }
  return try {
    val server = embeddedServer(CIO, host = "0.0.0.0", port = configuration.port) {
      if (forwarding.enabled) {
        bffWithGateway(configuration, resources.provider, resources.storage, forwarding, gateway = gateway) {
          if (forwarding.routes.bindings.any { it.prefix == "/api/orders" } &&
            forwarding.routes.bindings.any { it.prefix == "/orders" }
          ) {
            orderRoutes()
          }
          customRoutes()
        }
      } else {
        bff(configuration, resources.provider, resources.storage, customRoutes = customRoutes)
      }
    }
    server.monitor.subscribe(ApplicationStopped) { resources.close() }
    server.start(wait).application
  } catch (failure: Throwable) {
    resources.close()
    throw failure
  }
}
