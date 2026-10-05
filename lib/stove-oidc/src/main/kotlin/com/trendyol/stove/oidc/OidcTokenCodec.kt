package com.trendyol.stove.oidc

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.getOrElse
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.MissingNode
import io.ktor.client.request.*
import io.ktor.http.HttpMethod
import java.net.URLEncoder
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Translates the shared token model to and from OAuth wire representations. */
internal class OidcTokenCodec {
  private val json = ObjectMapper()

  fun encode(builder: HttpRequestBuilder, request: TokenRequest, testId: String?): HttpRequestBuilder {
    val form = grantParameters(request)
    builder.header("Content-Type", "application/x-www-form-urlencoded")
    builder.header("Accept", "application/json")
    if (testId != null) builder.header("X-Stove-Test-Id", testId)
    authenticate(builder, form, request.client)
    val body = form.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
    builder.method = HttpMethod.Post
    builder.setBody(body)
    return builder
  }

  private fun grantParameters(request: TokenRequest): MutableMap<String, String> {
    val form = linkedMapOf("grant_type" to request.grantType(), "client_id" to request.client.clientId)
    if (request.scopes.isNotEmpty()) form["scope"] = request.scopes.joinToString(" ")
    when (request) {
      is TokenRequest.ClientCredentials -> Unit
      is TokenRequest.AuthorizationCode -> addAuthorizationCode(form, request)
      is TokenRequest.RefreshToken -> form["refresh_token"] = request.refreshToken.value
    }
    return form
  }

  private fun addAuthorizationCode(form: MutableMap<String, String>, request: TokenRequest.AuthorizationCode) {
    form["code"] = request.code
    form["redirect_uri"] = request.redirectUri
    request.codeVerifier.onSome { form["code_verifier"] = it }
  }

  private fun authenticate(builder: HttpRequestBuilder, form: MutableMap<String, String>, client: OidcClient) {
    when (val auth = client.authentication) {
      ClientAuthentication.Public -> Unit

      is ClientAuthentication.SecretPost -> form["client_secret"] = auth.secret

      is ClientAuthentication.SecretBasic -> {
        form.remove("client_id")
        builder.header("Authorization", basicCredentials(client.clientId, auth.secret))
      }
    }
  }

  private fun basicCredentials(clientId: String, secret: String): String {
    val credentials = "${encode(clientId)}:${encode(secret)}"
    return "Basic ${Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))}"
  }

  fun decode(response: OidcHttpResponse): OidcTokenResponse {
    val document = parseResponse(response)
    if (response.status !in 200..299 || document.has("error")) {
      throw OidcTokenEndpointException(
        response.status,
        document.optionalText("error").getOrElse { "unknown_error" },
        document.optionalText("error_description"),
        response.body.decodeToString()
      )
    }
    if (!document.isObject) throw OidcOperationException("token response", "response must be a JSON object; response: $document")
    return OidcTokenResponse(
      accessToken = AccessToken(document.requiredText("access_token")),
      tokenType = document.requiredText("token_type"),
      idToken = document.optionalText("id_token").map(::IdToken),
      refreshToken = document.optionalText("refresh_token").map(::RefreshToken),
      expiresIn = expiry(document),
      scopes = document.optionalText("scope").map { it.split(' ').filter(String::isNotBlank).toSet() }
    )
  }

  private fun parseResponse(response: OidcHttpResponse): JsonNode = try {
    json.readTree(response.body) ?: MissingNode.getInstance()
  } catch (error: JsonProcessingException) {
    val body = response.body.decodeToString()
    if (response.status !in 200..299) {
      throw OidcTokenEndpointException(response.status, "unknown_error", responseBody = body, cause = error)
    }
    throw OidcOperationException("token response", "response must be valid JSON; response: $body", error)
  }

  private fun expiry(document: JsonNode): Option<Duration> {
    val value = document.path("expires_in")
    if (value.isMissingNode) return None
    if (!value.isIntegralNumber || !value.canConvertToLong() || value.longValue() < 0) {
      throw OidcOperationException("token response", "expires_in must be a non-negative integer; response: $document")
    }
    return Some(value.longValue().seconds)
  }

  private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
}

internal fun JsonNode.optionalText(name: String): Option<String> {
  val value = path(name)
  return if (value.isTextual) Some(value.textValue()) else None
}

internal fun JsonNode.requiredText(name: String): String =
  optionalText(name).filter(String::isNotBlank).getOrElse {
    throw OidcOperationException("response validation", "missing required text field '$name'; response: $this")
  }

internal fun TokenRequest.grantType(): String = when (this) {
  is TokenRequest.ClientCredentials -> "client_credentials"
  is TokenRequest.AuthorizationCode -> "authorization_code"
  is TokenRequest.RefreshToken -> "refresh_token"
}
