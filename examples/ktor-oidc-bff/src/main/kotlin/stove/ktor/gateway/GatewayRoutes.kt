package stove.ktor.gateway

import io.ktor.http.*
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@DslMarker
annotation class GatewayDsl

/** An immutable routing table compiled before the application accepts traffic. */
class GatewayRoutes internal constructor(val bindings: List<GatewayBinding>, val scopes: Set<String>) {
  init {
    require(bindings.map { it.prefix }.distinct().size == bindings.size) { "Gateway path bindings must be unique" }
  }

  internal fun resolve(uri: String): GatewayBinding {
    val path = uri.substringBefore('?')
    return bindings.filter { path == it.prefix || path.startsWith("${it.prefix}/") }.maxByOrNull { it.prefix.length }
      ?: throw GatewayFailure(HttpStatusCode.NotFound, "No gateway binding for this path")
  }

  companion object {
    val Empty = GatewayRoutes(emptyList(), emptySet())
  }
}

fun gatewayRoutes(configure: GatewayBuilder.() -> Unit): GatewayRoutes = GatewayBuilder().apply(configure).build()

@GatewayDsl
class GatewayBuilder {
  private val services = linkedMapOf<String, GatewayServiceBuilder>()

  fun service(name: String, upstream: String, configure: GatewayServiceBuilder.() -> Unit) {
    require(name.isNotBlank() && name !in services) { "Gateway service names must be nonblank and unique: $name" }
    services[name] = GatewayServiceBuilder(name, upstream).apply(configure)
  }

  internal fun build() = GatewayRoutes(services.values.flatMap { it.bindings() }, services.values.flatMap { it.scopes }.toSet())
}

@GatewayDsl
class GatewayServiceBuilder internal constructor(private val name: String, private val upstream: String) {
  internal val scopes = linkedSetOf<String>()
  private val routes = mutableListOf<GatewayRouteBuilder>()

  fun scopes(vararg values: String) {
    require(values.all { it.isNotBlank() && it.none(Char::isWhitespace) }) { "Expected individual OAuth scopes" }
    scopes.addAll(values)
  }

  fun route(path: String, configure: GatewayRouteBuilder.() -> Unit = {}) {
    routes += GatewayRouteBuilder(path).apply(configure)
  }

  internal fun bindings(): List<GatewayBinding> {
    require(routes.isNotEmpty()) { "Service $name requires a route" }
    val uri = URI(upstream)
    require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) {
      "Service $name requires an HTTP(S) upstream without credentials, query or fragment"
    }
    validatePath(uri.rawPath.ifEmpty { "/" })
    return routes.map { it.build(name, Url(upstream)) }
  }
}

@GatewayDsl
class GatewayRouteBuilder internal constructor(private val path: String) {
  private val before = mutableListOf<suspend GatewayForwardContext.() -> Unit>()
  private val after = mutableListOf<suspend GatewayAfterForwardContext.() -> Unit>()
  var upstreamPath: String = path.removeSuffix("/**")
  var methods: Set<HttpMethod> = setOf(HttpMethod.Get, HttpMethod.Head)
  var timeout: Duration = 5.seconds
  var requestLimitBytes: Int = 1024 * 1024
  var responseLimitBytes: Int = 1024 * 1024
  var requestHeaders: Set<String> = GatewayHeaders.DefaultRequest
  var responseHeaders: Set<String> = GatewayHeaders.DefaultResponse
  private var transport: GatewayTransport = GatewayTransport.Http

  fun sse(configure: GatewaySseBuilder.() -> Unit = {}) {
    selectTransport(GatewaySseBuilder().apply(configure).build())
  }

  fun webSocket(configure: GatewayWebSocketBuilder.() -> Unit = {}) {
    selectTransport(GatewayWebSocketBuilder().apply(configure).build())
  }

  private fun selectTransport(value: GatewayTransport) {
    require(transport == GatewayTransport.Http) { "A route can select only one streaming transport" }
    transport = value
    methods = setOf(HttpMethod.Get)
  }

  /** Continue forwarding automatically unless the hook responds or throws. */
  fun beforeForward(handler: suspend GatewayForwardContext.() -> Unit) {
    before += handler
  }

  /** Send the resulting response automatically unless the hook responds or throws. */
  fun afterForward(handler: suspend GatewayAfterForwardContext.() -> Unit) {
    after += handler
  }

  internal fun build(service: String, upstream: Url): GatewayBinding {
    require(path.endsWith("/**")) { "Gateway route must be a subtree pattern, such as /orders/**: $path" }
    val prefix = path.removeSuffix("/**")
    validatePath(prefix)
    require(prefix != "/" && prefix.isNotEmpty()) { "Gateway routes must have a non-root prefix" }
    validatePath(upstreamPath)
    require(methods.isNotEmpty() && methods.all { it in supportedMethods }) { "Unsupported or empty gateway method policy" }
    require(transport == GatewayTransport.Http || methods == setOf(HttpMethod.Get)) { "SSE and WebSocket routes require GET" }
    require(timeout.inWholeMilliseconds in 1..60_000) { "Gateway timeout must be between 1ms and 60s" }
    require(requestLimitBytes in 1..16 * 1024 * 1024 && responseLimitBytes in 1..16 * 1024 * 1024) {
      "Gateway body limits must be between 1 byte and 16 MiB"
    }
    return GatewayBinding(
      service, prefix, upstream, upstreamPath, methods.toSet(), timeout,
      requestLimitBytes, responseLimitBytes,
      GatewayHeaders(if (transport is GatewayTransport.Sse) requestHeaders + "Last-Event-ID" else requestHeaders, responseHeaders),
      before.toList(), after.toList(), transport
    )
  }
}

class GatewayBinding internal constructor(
  val service: String,
  val prefix: String,
  private val upstream: Url,
  private val upstreamPath: String,
  val methods: Set<HttpMethod>,
  val timeout: Duration,
  val requestLimitBytes: Int,
  val responseLimitBytes: Int,
  val headers: GatewayHeaders,
  internal val beforeForward: List<suspend GatewayForwardContext.() -> Unit> = emptyList(),
  internal val afterForward: List<suspend GatewayAfterForwardContext.() -> Unit> = emptyList(),
  val transport: GatewayTransport = GatewayTransport.Http
) {
  /** Preserve the encoded suffix and query; a browser-controlled URL can never replace the upstream. */
  fun target(requestUri: String): Url {
    if ('#' in requestUri) throw GatewayFailure(HttpStatusCode.BadRequest, "Fragments are not valid in an HTTP request target")
    val path = requestUri.substringBefore('?')
    if (path != prefix &&
      !path.startsWith("$prefix/")
    ) {
      throw GatewayFailure(HttpStatusCode.BadRequest, "Path does not match gateway binding")
    }
    val suffix = path.removePrefix(prefix)
    validateSuffix(suffix)
    val query = if ('?' in requestUri) "?${requestUri.substringAfter('?')}" else ""
    return Url("${upstream.protocolWithAuthority}${upstream.encodedPath.trimEnd('/')}${upstreamPath.trimEnd('/')}$suffix$query")
  }
}

private val supportedMethods =
  setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete, HttpMethod.Options)

internal fun validatePath(path: String) {
  require(path.startsWith('/') && (path == "/" || !path.endsWith('/')) && !path.contains("//")) {
    "Expected an absolute path without a trailing slash: $path"
  }
  require(
    path.all {
      (it.isLetterOrDigit() && it.code < 128) || it in "/-._~"
    } && path.split('/').none { it == "." || it == ".." }
  ) { "Invalid gateway path: $path" }
}

private fun validateSuffix(suffix: String) {
  val valid = try {
    suffix.split('/').all { segment ->
      val decoded = segment.decodeURLPart()
      decoded != "." && decoded != ".." && decoded.none { it == '/' || it == '\\' || it == '%' || it.code < 32 || it.code == 127 }
    }
  } catch (_: URLDecodeException) {
    false
  }
  if (!valid || suffix.contains("//")) throw GatewayFailure(HttpStatusCode.BadRequest, "Ambiguous gateway path")
}

class GatewayFailure(val status: HttpStatusCode, message: String, cause: Throwable? = null) : RuntimeException(message, cause)
