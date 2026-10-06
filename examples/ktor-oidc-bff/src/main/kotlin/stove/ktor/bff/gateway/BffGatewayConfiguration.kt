package stove.ktor.bff.gateway

import stove.ktor.gateway.*
import java.nio.file.Files
import java.nio.file.Path

/** Optional forwarding configuration; core BFF configuration has no gateway dependency. */
data class BffGatewayConfiguration(val routes: GatewayRoutes) {
  init {
    val reserved = listOf("/auth", "/api/session", "/api/profile", "/health", "/app.js", "/style.css")
    require(
      routes.bindings.none { route ->
        reserved.any { it == route.prefix || it.startsWith("${route.prefix}/") || route.prefix.startsWith("$it/") }
      }
    ) { "Gateway routes must not overlap the BFF's authentication or application endpoints" }
  }

  val enabled: Boolean get() = routes.bindings.isNotEmpty()

  companion object {
    fun load(args: Array<String>, environment: Map<String, String> = System.getenv()): BffGatewayConfiguration {
      val settings = environment + args.associate { it.removePrefix("--").substringBefore('=') to it.substringAfter('=') }
      val file = settings.getOrDefault("BFF_ROUTES_FILE", "")
      val routes = if (file.isBlank()) {
        defaultRoutes(settings.getOrDefault("RESOURCE_API_URL", "").trimEnd('/'))
      } else {
        GatewayConfiguration.parse(Files.readString(Path.of(file)), settings)
      }
      return BffGatewayConfiguration(routes)
    }
  }
}
