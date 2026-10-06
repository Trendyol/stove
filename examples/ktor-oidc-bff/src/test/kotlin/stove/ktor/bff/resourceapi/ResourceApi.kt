@file:Suppress("ExtractKtorModule")

package stove.ktor.bff.resourceapi

import com.nimbusds.jose.jwk.JWKSet
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import stove.ktor.oidc.server.*
import io.ktor.client.engine.cio.CIO as ClientCIO

/** A real HTTP resource server owned by the BFF test suite, including native BFF runs. */
class ResourceApi private constructor(private val server: EmbeddedServer<*, *>, val origin: String) {
  suspend fun stop() = server.stopSuspend(0, 1000)

  companion object {
    suspend fun start(issuer: String, requireDpop: Boolean): ResourceApi {
      val verifier = loadVerifier(issuer)
      val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
        resourceApi(verifier, requireDpop)
      }
      return try {
        server.startSuspend(wait = false)
        val origin = "http://127.0.0.1:${server.engine.resolvedConnectors().single().port}"
        HttpClient(ClientCIO) {
          install(HttpTimeout) { requestTimeoutMillis = 5000 }
        }.use { check(it.get("$origin/health").status == HttpStatusCode.OK) }
        ResourceApi(server, origin)
      } catch (failure: Throwable) {
        withContext(NonCancellable) {
          try {
            server.stopSuspend(0, 1000)
          } catch (cleanup: Throwable) {
            failure.addSuppressed(cleanup)
          }
        }
        throw failure
      }
    }

    private suspend fun loadVerifier(issuer: String): AccessTokenVerifier = HttpClient(ClientCIO) {
      expectSuccess = true
      followRedirects = false
      install(HttpTimeout) { requestTimeoutMillis = 5000 }
    }.use { http ->
      val metadata = Json.parseToJsonElement(http.get("$issuer/.well-known/openid-configuration").bodyAsText()).jsonObject
      check(metadata.getValue("issuer").jsonPrimitive.content == issuer)
      val keys = JWKSet.parse(http.get(metadata.getValue("jwks_uri").jsonPrimitive.content).bodyAsText())
      AccessTokenVerifier(issuer, "orders-api", keys)
    }
  }
}

private fun Application.resourceApi(verifier: AccessTokenVerifier, requireDpop: Boolean) {
  install(WebSockets) {
    channels {
      incoming = bounded(8)
      outgoing = bounded(8)
    }
  }
  install(Authentication) {
    oidc("access") {
      verifyAccessToken = verifier::verify
      this.requireDpop = requireDpop
      // Connector configuration is trusted; Host/Forwarded headers cannot change the proof target.
      targetUri = { call -> "http://127.0.0.1:${call.request.local.localPort}${call.request.path()}" }
    }
  }
  routing {
    get("/health") { call.respondText("ready") }
    authenticate("access") {
      requireScopes("access", "orders:read") {
        get("/orders") { call.orders() }
        gatewayEndpoints()
        orderStreams()
      }
    }
  }
}

private suspend fun ApplicationCall.orders() {
  val identity = checkNotNull(principal<VerifiedAccessToken>("access"))
  response.header(HttpHeaders.CacheControl, "no-store")
  val body = buildJsonObject {
    put("subject", identity.subject)
    putJsonArray("orders") {
      addJsonObject {
        put("id", "order-1001")
        put("status", "confirmed")
      }
    }
  }
  respondText(body.toString(), ContentType.Application.Json)
}
