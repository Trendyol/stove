package stove.ktor.oidc.client

import io.ktor.client.call.*
import io.ktor.client.plugins.api.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.AttributeKey
import io.ktor.utils.io.*
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.io.IOException

/** Credentials are request-scoped, so one HttpClient can safely serve many browser sessions. */
val OidcAuthentication = createClientPlugin("OidcAuthentication") {
  onRequest { request, _ ->
    val authentication = request.attributes.getOrNull(AuthenticationKey) ?: return@onRequest
    request.attributes.put(StateKey, RequestState(authentication.resolve(request.url.build())))
  }
  on(SendingRequest) { request, _ ->
    val state = request.attributes.getOrNull(StateKey) ?: return@on
    state.credentials.apply(request)
  }
  on(Send) { request ->
    val first = proceed(request)
    val state = request.attributes.getOrNull(StateKey) ?: return@on first
    val credentials = state.credentials
    val nonce = first.response.headers["DPoP-Nonce"].orEmpty()
    credentials.binding.rememberNonce(first.request.url.toString(), nonce)
    if (state.nonceRetried || credentials.binding !is TokenBinding.Dpop || nonce.isBlank() ||
      first.response.status.value !in setOf(400, 401) || !request.body.isReplayable()
    ) {
      return@on first
    }
    // Preserve an inspected error response for the caller, even when it is not a nonce challenge.
    val saved = first.saveChallenge()
    if (!saved.response.isNonceChallenge()) return@on saved
    state.nonceRetried = true
    val retry = proceed(HttpRequestBuilder().takeFrom(request))
    credentials.binding.rememberNonce(retry.request.url.toString(), retry.response.headers["DPoP-Nonce"].orEmpty())
    retry
  }
}

/** Apply a proof to a token endpoint request; client authentication remains part of the grant. */
fun HttpRequestBuilder.oidcToken(binding: TokenBinding) {
  attributes.put(AuthenticationKey, RequestAuthentication.Token(binding))
}

/** Resolve a fresh token once per logical request, using the application's refresh coordination. */
fun HttpRequestBuilder.oidcResource(binding: TokenBinding, accessToken: suspend () -> String) {
  attributes.put(AuthenticationKey, RequestAuthentication.Resource(binding, accessToken))
}

private sealed interface RequestAuthentication {
  suspend fun resolve(target: Url): Credentials

  data class Token(val binding: TokenBinding) : RequestAuthentication {
    override suspend fun resolve(target: Url) = Credentials.Token(binding, target)
  }

  data class Resource(val binding: TokenBinding, val accessToken: suspend () -> String) : RequestAuthentication {
    override suspend fun resolve(
      target: Url
    ) = Credentials.Resource(binding, target, accessToken().also { require(it.isNotBlank()) })
  }
}

private sealed interface Credentials {
  val binding: TokenBinding
  fun apply(request: HttpRequestBuilder)

  data class Token(override val binding: TokenBinding, val target: Url) : Credentials {
    override fun apply(request: HttpRequestBuilder) {
      // A redirect must never carry client secrets or grant credentials to another endpoint.
      if (request.url.build() != target) throw OidcRedirectRejected()
      request.headers.remove("DPoP")
      binding.tokenRequest(request, target.toString())
    }
  }

  data class Resource(override val binding: TokenBinding, val target: Url, val accessToken: String) : Credentials {
    override fun apply(request: HttpRequestBuilder) {
      val outgoing = request.url.build()
      // HttpRedirect copies attributes; enforce the original origin before each engine send.
      if (outgoing.protocolWithAuthority != target.protocolWithAuthority) throw OidcRedirectRejected()
      request.headers.remove("DPoP")
      request.headers.remove(HttpHeaders.Authorization)
      binding.resourceRequest(request, outgoing.toString(), accessToken)
    }
  }
}

class OidcRedirectRejected : IllegalStateException("OIDC credentials cannot follow this redirect")

private val AuthenticationKey = AttributeKey<RequestAuthentication>("OidcRequestAuthentication")
private class RequestState(val credentials: Credentials, var nonceRetried: Boolean = false)
private val StateKey = AttributeKey<RequestState>("OidcRequestState")
private fun Any.isReplayable(): Boolean = this is OutgoingContent.NoContent || this is OutgoingContent.ByteArrayContent

/** Challenge inspection must not turn a bounded/streaming caller into an unbounded response buffer. */
@OptIn(InternalAPI::class)
private suspend fun HttpClientCall.saveChallenge(): HttpClientCall {
  // A rejected upgrade has HTTP content; bypass WebSocket session transformation.
  val channel = response.rawContent
  val body = channel.readBuffer(64 * 1024L + 1).readByteArray()
  if (body.size > 64 * 1024) {
    val failure = IOException("OIDC nonce challenge exceeds 64 KiB")
    channel.cancel(failure)
    throw failure
  }
  return replaceResponse { ByteReadChannel(body) }.save()
}

@OptIn(InternalAPI::class)
private suspend fun HttpResponse.isNonceChallenge(): Boolean {
  if (headers[HttpHeaders.WWWAuthenticate].orEmpty().contains("error=\"use_dpop_nonce\"")) return true
  return try {
    val body = Json.parseToJsonElement(rawContent.readBuffer().readByteArray().toString(Charsets.UTF_8)) as? JsonObject ?: return false
    (body["error"] as? JsonPrimitive)?.content == "use_dpop_nonce"
  } catch (_: SerializationException) {
    false
  }
}
