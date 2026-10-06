package stove.ktor.gateway.internal

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.coroutines.*
import stove.ktor.gateway.*
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class GatewayRuntime(private val configuration: GatewayConfig) : AutoCloseable {
  val routes = configuration.routes
  private val boundHooks = configuration.hooks.bind(routes)
  private val active = ConcurrentHashMap.newKeySet<Job>()
  private val closed = AtomicBoolean()
  private val client = configuration.createClient {
    install(HttpTimeout)
    install(WebSockets) {
      maxFrameSize = routes.bindings.map { it.transport }.filterIsInstance<GatewayTransport.WebSocket>()
        .maxOfOrNull { it.maxFrameBytes }?.toLong() ?: 64 * 1024L
      channels {
        incoming = bounded(8)
        outgoing = bounded(8)
      }
    }
    followRedirects = false
    expectSuccess = false
    HttpResponseValidator {
      // Client pipeline failures are separate from application hook exceptions.
      handleResponseExceptionWithRequest { error, _ ->
        val bodyFailure = generateSequence(error) { it.cause }.take(16).filterIsInstance<GatewayFailure>().firstOrNull()
        if (bodyFailure != null) throw bodyFailure
        when (error) {
          is HttpRequestTimeoutException, is ConnectTimeoutException, is SocketTimeoutException -> throw GatewayFailure(
            HttpStatusCode.GatewayTimeout,
            "Upstream request timed out",
            error
          )

          is IOException -> throw GatewayFailure(HttpStatusCode.BadGateway, "Upstream service unavailable", error)
        }
      }
    }
  }
  private val http = GatewayHttpTransport(client)
  private val sockets = GatewayWebSocketTransport(client, configuration.webSocketOrigins.toSet(), configuration.allowMissingWebSocketOrigin)

  suspend fun forward(call: ApplicationCall, binding: GatewayBinding): Unit = tracked {
    checkMethod(call, call.request.httpMethod, binding)
    binding.target(call.request.uri)
    val socket = binding.transport is GatewayTransport.WebSocket
    if (socket) {
      sockets.validate(call, binding.transport)
    } else if (call.request.headers[HttpHeaders.Upgrade] !=
      null
    ) {
      throw GatewayFailure(HttpStatusCode.BadRequest, "Route does not accept upgrades")
    }
    if (binding.transport != GatewayTransport.Http &&
      (call.request.contentLength()?.let { it != 0L } == true || call.request.headers[HttpHeaders.TransferEncoding] != null)
    ) {
      throw GatewayFailure(HttpStatusCode.BadRequest, "Streaming handshakes cannot contain a request body")
    }
    val incoming = if (socket) {
      GatewayBody.bytes(byteArrayOf(), binding.requestLimitBytes, HttpStatusCode.PayloadTooLarge)
    } else {
      GatewayBody.stream(call.receiveChannel(), call.request.contentLength(), binding.requestLimitBytes, HttpStatusCode.PayloadTooLarge)
    }
    val outgoing = GatewayRequestBuilder(call.request.headers, incoming, binding.requestLimitBytes)
    try {
      incoming.checkLength()
      val context = GatewayForwardContext(call, call.request.uri, call.request.httpMethod, outgoing)
      for (handler in boundHooks.getValue(binding.prefix).before) {
        handler(context)
        if (call.isHandled) return@tracked
      }
      val request = GatewayRequest(context.uri, context.method, outgoing.headers.build(), outgoing.body)
      checkBody(binding, request)
      val access = configuration.access(call)
      if (socket) {
        sockets.forward(call, binding, request, access) { response, upgrade -> after(call, binding, response, upgrade) }
      } else {
        access.whileAuthorized {
          http.execute(binding, request, access) { response -> after(call, binding, response) { call.respondGateway(it) } }
        }
      }
    } finally {
      outgoing.body.cancel()
    }
  }

  suspend fun <T> request(
    call: ApplicationCall,
    uri: String,
    method: HttpMethod,
    configure: GatewayRequestBuilder.() -> Unit,
    response: suspend (GatewayResponse) -> T
  ): T = tracked {
    val binding = routes.resolve(uri)
    checkMethod(call, method, binding)
    if (binding.transport is GatewayTransport.WebSocket) {
      throw GatewayFailure(HttpStatusCode.BadRequest, "WebSocket bindings must be used through their upgrade route")
    }
    if (call.request.httpMethod in safeMethods && method !in safeMethods) {
      throw GatewayFailure(HttpStatusCode.MethodNotAllowed, "An upstream mutation requires an unsafe browser method")
    }
    binding.target(uri)
    val empty = GatewayBody.bytes(byteArrayOf(), binding.requestLimitBytes, HttpStatusCode.PayloadTooLarge)
    val outgoing = GatewayRequestBuilder(call.request.headers, empty, binding.requestLimitBytes)
    try {
      outgoing.configure()
      val request = GatewayRequest(uri, method, outgoing.headers.build(), outgoing.body)
      checkBody(binding, request)
      val access = configuration.access(call)
      access.whileAuthorized { http.execute(binding, request, access, response) }
    } finally {
      outgoing.body.cancel()
    }
  }

  private suspend fun after(
    call: ApplicationCall,
    binding: GatewayBinding,
    upstream: GatewayResponse,
    forward: suspend (GatewayResponse) -> Unit
  ) {
    val hooks = boundHooks.getValue(binding.prefix).after
    if (hooks.isEmpty()) return forward(upstream)
    val response = GatewayResponseBuilder(upstream, binding.responseLimitBytes)
    val context = GatewayAfterForwardContext(call, call.request.uri, upstream.method, response)
    try {
      for (handler in hooks) {
        handler(context)
        if (call.isHandled) return
      }
      forward(response.build(binding))
    } finally {
      response.body.cancel()
    }
  }

  private fun checkBody(binding: GatewayBinding, outgoing: GatewayRequest) {
    outgoing.body.checkLength()
    if (binding.transport != GatewayTransport.Http && outgoing.body.contentLength != 0L) {
      throw GatewayFailure(HttpStatusCode.BadRequest, "Streaming handshakes cannot contain a request body")
    }
  }

  private fun checkMethod(call: ApplicationCall, method: HttpMethod, binding: GatewayBinding) {
    if (method !in binding.methods) {
      call.response.header(HttpHeaders.Allow, binding.methods.joinToString(", ") { it.value })
      throw GatewayFailure(HttpStatusCode.MethodNotAllowed, "Method is not enabled for this gateway route")
    }
  }

  private suspend fun <T> tracked(block: suspend () -> T): T = coroutineScope {
    val job = currentCoroutineContext().job
    active.add(job)
    try {
      if (closed.get()) throw CancellationException("Gateway is stopping")
      block()
    } finally {
      active.remove(job)
    }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    active.forEach { it.cancel(CancellationException("Gateway is stopping")) }
    client.close()
  }
}

private val safeMethods = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)
