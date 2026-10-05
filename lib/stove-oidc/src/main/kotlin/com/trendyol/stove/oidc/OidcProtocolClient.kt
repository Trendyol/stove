package com.trendyol.stove.oidc

import arrow.core.Option
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import java.io.IOException
import javax.net.ssl.SSLException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal class OidcProtocolClient(private val options: OidcSystemOptions) : AutoCloseable {
  private val json = ObjectMapper()
  private val transport = OidcHttpTransport(options)
  private val tokens = OidcTokenCodec()

  suspend fun awaitReady(issuer: String): OidcEndpoints = withTimeout(options.readinessTimeout) { pollDiscovery(issuer) }

  private suspend fun pollDiscovery(issuer: String): OidcEndpoints {
    while (currentCoroutineContext().isActive) {
      try {
        return discoverWithKeys(issuer)
      } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        if (!error.isRetryableDiscoveryFailure()) throw error
      }
      delay(100.milliseconds)
    }
    throw CancellationException("OIDC discovery cancelled")
  }

  private fun Exception.isRetryableDiscoveryFailure(): Boolean = when (this) {
    is MetadataNotReadyException, is TimeoutCancellationException -> true
    is JsonProcessingException, is SSLException -> false
    is IOException -> true
    else -> false
  }

  private suspend fun discoverWithKeys(issuer: String): OidcEndpoints {
    val metadata = discover(issuer, options.requestTimeout)
    checkKeys(metadata.jwksUri, options.requestTimeout)
    return metadata
  }

  suspend fun discover(issuer: String, timeout: Duration): OidcEndpoints {
    val issuerUri = httpUri(issuer)
    check(issuerUri.rawQuery == null && issuerUri.rawFragment == null)
    val discoveryUrl = "${issuer.trimEnd('/')}/.well-known/openid-configuration"
    val document = get(discoveryUrl, timeout)
    val advertisedIssuer = document.requiredText("issuer")
    if (advertisedIssuer != issuer) {
      throw OidcOperationException(
        "discovery",
        "issuer mismatch: expected '$issuer', received '$advertisedIssuer'; align issuerUrl with the provider's hostname configuration"
      )
    }
    return OidcEndpoints(
      issuerUrl = issuer,
      discoveryUrl = discoveryUrl,
      jwksUri = document.requiredEndpoint("jwks_uri"),
      tokenEndpoint = document.requiredEndpoint("token_endpoint"),
      authorizationEndpoint = document.requiredEndpoint("authorization_endpoint"),
      userInfoEndpoint = document.optionalEndpoint("userinfo_endpoint"),
      endSessionEndpoint = document.optionalEndpoint("end_session_endpoint")
    )
  }

  suspend fun checkKeys(uri: String, timeout: Duration) {
    val keys = get(uri, timeout, "JWKS").path("keys")
    if (!keys.isArray) throw OidcOperationException("JWKS", "$uri must contain a keys array; received: $keys")
    if (keys.isEmpty) throw MetadataNotReadyException()
  }

  private suspend fun get(uri: String, timeout: Duration, stage: String = "discovery"): JsonNode {
    val response = transport.send(transport.request(uri, timeout))
    if (response.status in setOf(404, 408, 429) || response.status in 500..599) throw MetadataNotReadyException()
    if (response.status != 200) {
      throw OidcOperationException(
        stage,
        "metadata request to $uri rejected (HTTP ${response.status}); response: ${response.body.decodeToString()}"
      )
    }
    return try {
      json.readTree(response.body)?.takeIf { it.isObject }
        ?: throw OidcOperationException(stage, "metadata must be a JSON object; $uri returned: ${response.body.decodeToString()}")
    } catch (error: JsonProcessingException) {
      throw OidcOperationException(stage, "metadata must be valid JSON; $uri returned: ${response.body.decodeToString()}", error)
    }
  }

  suspend fun token(endpoint: String, request: TokenRequest, testId: String?): OidcTokenResponse = withOidcContext("token request") {
    val httpRequest = tokens.encode(transport.request(endpoint), request, testId)
    tokens.decode(transport.send(httpRequest))
  }

  override fun close() = transport.close()

  private fun JsonNode.requiredEndpoint(name: String): String = requiredText(name).also { httpUri(it) }
  private fun JsonNode.optionalEndpoint(name: String): Option<String> = optionalText(name).map { it.also(::httpUri) }
}

private class MetadataNotReadyException : RuntimeException("OIDC metadata is not ready")
