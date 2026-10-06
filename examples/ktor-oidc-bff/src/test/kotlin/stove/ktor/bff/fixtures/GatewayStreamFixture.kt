package stove.ktor.bff.fixtures

import com.trendyol.stove.system.PortFinder
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.withTimeout
import stove.ktor.bff.gateway.OidcGatewayAccess
import stove.ktor.bff.gateway.oidc
import stove.ktor.bff.resourceapi.*
import stove.ktor.gateway.*
import stove.ktor.oidc.client.TokenBinding
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import io.ktor.client.engine.cio.CIO as ClientCIO

/** Real CIO servers on both sides; test-host buffering cannot mask streaming regressions. */
class GatewayStreamFixture(
  limit: Int,
  binding: TokenBinding,
  configure: GatewayHooks.() -> Unit
) : AutoCloseable {
  val state = StreamingApiState()
  val invalidations = AtomicInteger()
  private val upstreamPort = PortFinder.findAvailablePort()
  private val upstream = embeddedServer(CIO, host = "127.0.0.1", port = upstreamPort) { gatewayStreamingApi(state) }.start(wait = false)
  private val bffPort = PortFinder.findAvailablePort()
  val origin = "http://127.0.0.1:$bffPort"
  private val bff = embeddedServer(CIO, host = "127.0.0.1", port = bffPort) {
    streamingBff(
      routes = streamingRoutes("http://127.0.0.1:$upstreamPort", limit),
      credentials = OidcGatewayAccess(binding, { "access" }, { invalidations.incrementAndGet() }),
      configure = configure
    )
  }.start(wait = false)
  val client = HttpClient(ClientCIO) {
    install(HttpTimeout) { requestTimeoutMillis = 5_000 }
  }

  override fun close() {
    state.releaseDownload.complete(Unit)
    client.close()
    bff.stop(0, 1_000)
    upstream.stop(0, 1_000)
  }
}

private fun streamingRoutes(upstream: String, limit: Int) = gatewayRoutes {
  service("stream", upstream) {
    route("/api/**") {
      upstreamPath = "/stream"
      methods = setOf(HttpMethod.Get, HttpMethod.Post)
      requestLimitBytes = limit
      responseLimitBytes = limit
      responseHeaders += "X-Stream"
    }
  }
}

private fun Application.streamingBff(routes: GatewayRoutes, credentials: OidcGatewayAccess, configure: GatewayHooks.() -> Unit) {
  install(Gateway) {
    this.routes = routes
    hooks = GatewayHooks().apply(configure)
    oidc { credentials }
  }
  install(StatusPages) {
    exception<GatewayFailure> { call, error ->
      if (call.response.isCommitted) throw error
      call.respondText(error.message.orEmpty(), status = error.status)
    }
    exception<IOException> { call, error ->
      if (call.response.isCommitted) throw error
      call.respondText(error.message.orEmpty(), status = HttpStatusCode.InternalServerError)
    }
  }
  routing {
    gateway()
    get("/custom") { call.gatewayRequest("/api/download") { call.respondGateway(it) } }
  }
}

suspend fun streamingGatewayTest(
  limit: Int = 16 * 1024 * 1024,
  binding: TokenBinding = TokenBinding.Bearer,
  configure: GatewayHooks.() -> Unit = {},
  test: suspend GatewayStreamFixture.() -> Unit
) {
  GatewayStreamFixture(limit, binding, configure).use { fixture ->
    withTimeout(10_000) { fixture.test() }
  }
}
