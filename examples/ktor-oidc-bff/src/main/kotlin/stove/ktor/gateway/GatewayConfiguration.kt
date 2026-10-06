package stove.ktor.gateway

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.*
import kotlin.time.Duration.Companion.milliseconds

/** The file format compiles through the same validated DSL as programmatic configuration. */
object GatewayConfiguration {
  fun parse(text: String, environment: Map<String, String> = emptyMap()): GatewayRoutes {
    val root = Json.parseToJsonElement(text).jsonObject
    root.only("services")
    return gatewayRoutes {
      for (item in root.getValue("services").jsonArray) {
        val definition = item.jsonObject
        definition.only("name", "upstream", "scopes", "routes")
        service(definition.string("name"), definition.string("upstream").expand(environment)) {
          scopes(*definition.strings("scopes").toTypedArray())
          for (route in definition.getValue("routes").jsonArray) addRoute(route.jsonObject)
        }
      }
    }
  }
}

private fun GatewayServiceBuilder.addRoute(definition: JsonObject) {
  definition.only(
    "path",
    "upstreamPath",
    "methods",
    "timeoutMillis",
    "requestLimitBytes",
    "responseLimitBytes",
    "requestHeaders",
    "responseHeaders",
    "transport",
    "sse",
    "websocket"
  )
  route(definition.string("path")) {
    if ("upstreamPath" in definition) upstreamPath = definition.string("upstreamPath")
    configureTransport(definition)
    if ("methods" in definition) methods = definition.strings("methods").map(HttpMethod::parse).toSet()
    if ("timeoutMillis" in definition) timeout = definition.getValue("timeoutMillis").jsonPrimitive.long.milliseconds
    if ("requestLimitBytes" in definition) requestLimitBytes = definition.getValue("requestLimitBytes").jsonPrimitive.int
    if ("responseLimitBytes" in definition) responseLimitBytes = definition.getValue("responseLimitBytes").jsonPrimitive.int
    if ("requestHeaders" in definition) requestHeaders = definition.strings("requestHeaders").toSet()
    if ("responseHeaders" in definition) responseHeaders = definition.strings("responseHeaders").toSet()
  }
}

private fun JsonObject.only(vararg supported: String) {
  require(keys.all { it in supported }) { "Unknown gateway configuration fields: ${keys - supported.toSet()}" }
}
private fun JsonObject.string(name: String): String = getValue(name).string()
private fun JsonElement.string(): String {
  require(this is JsonPrimitive && isString) { "Expected a JSON string" }
  return content
}
private fun JsonObject.strings(name: String): List<String> = if (name in this) getValue(name).jsonArray.map { it.string() } else emptyList()
private fun String.expand(environment: Map<String, String>): String = Regex("\\$\\{([A-Z][A-Z0-9_]*)}").replace(this) {
  environment[it.groupValues[1]] ?: error("Missing gateway environment variable ${it.groupValues[1]}")
}

private fun GatewayRouteBuilder.configureTransport(definition: JsonObject) {
  val transport = if ("transport" in definition) definition.string("transport") else "http"
  require("sse" !in definition || transport == "sse") { "SSE options require the sse transport" }
  require("websocket" !in definition || transport == "websocket") { "WebSocket options require the websocket transport" }
  when (transport) {
    "http" -> Unit

    "sse" -> sse {
      val options = definition["sse"]?.jsonObject ?: JsonObject(emptyMap())
      options.only("idleTimeoutMillis")
      if ("idleTimeoutMillis" in options) idleTimeout = options.getValue("idleTimeoutMillis").jsonPrimitive.long.milliseconds
    }

    "websocket" -> webSocket {
      val options = definition["websocket"]?.jsonObject ?: JsonObject(emptyMap())
      options.only("idleTimeoutMillis", "closeTimeoutMillis", "maxFrameBytes", "maxMessageBytes", "subprotocols", "requireSubprotocol")
      if ("idleTimeoutMillis" in options) idleTimeout = options.getValue("idleTimeoutMillis").jsonPrimitive.long.milliseconds
      if ("closeTimeoutMillis" in options) closeTimeout = options.getValue("closeTimeoutMillis").jsonPrimitive.long.milliseconds
      if ("maxFrameBytes" in options) maxFrameBytes = options.getValue("maxFrameBytes").jsonPrimitive.int
      if ("maxMessageBytes" in options) maxMessageBytes = options.getValue("maxMessageBytes").jsonPrimitive.int
      if ("subprotocols" in options) subprotocols = options.strings("subprotocols").toSet()
      if ("requireSubprotocol" in options) requireSubprotocol = options.getValue("requireSubprotocol").jsonPrimitive.boolean
    }

    else -> error("Unknown gateway transport: $transport")
  }
}
