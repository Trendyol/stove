package com.trendyol.stove.oidc

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import kotlin.time.Duration

/** A test access token. Use [value] when passing it to clients. */
class AccessToken(val value: String) {
  /** Adapts this access token to Stove HTTP's bearer-token argument. */
  fun asHttpToken(): Option<String> = Some(value)

  override fun toString(): String = "AccessToken(value=$value)"
}

class IdToken(val value: String) {
  override fun toString(): String = "IdToken(value=$value)"
}

class RefreshToken(val value: String) {
  override fun toString(): String = "RefreshToken(value=$value)"
}

sealed interface ClientAuthentication {
  data object Public : ClientAuthentication
  class SecretBasic(val secret: String) : ClientAuthentication {
    override fun toString(): String = "SecretBasic(secret=$secret)"
  }
  class SecretPost(val secret: String) : ClientAuthentication {
    override fun toString(): String = "SecretPost(secret=$secret)"
  }
}

class OidcClient(val clientId: String, val authentication: ClientAuthentication = ClientAuthentication.Public) {
  override fun toString(): String = "OidcClient(clientId=$clientId, authentication=$authentication)"
}

sealed class TokenRequest(val client: OidcClient, scopes: Set<String>) {
  val scopes: Set<String> = scopes.toSet()
  class ClientCredentials(client: OidcClient, scopes: Set<String> = emptySet()) : TokenRequest(client, scopes)
  class AuthorizationCode(
    client: OidcClient,
    val code: String,
    val redirectUri: String,
    val codeVerifier: Option<String> = None
  ) : TokenRequest(client, emptySet())
  class RefreshToken(
    client: OidcClient,
    val refreshToken: com.trendyol.stove.oidc.RefreshToken,
    scopes: Set<String> = emptySet()
  ) : TokenRequest(client, scopes)

  final override fun toString(): String = when (this) {
    is ClientCredentials -> "ClientCredentials(client=$client, scopes=$scopes)"
    is AuthorizationCode -> "AuthorizationCode(client=$client, code=$code, redirectUri=$redirectUri, codeVerifier=$codeVerifier)"
    is RefreshToken -> "RefreshToken(client=$client, refreshToken=$refreshToken, scopes=$scopes)"
  }
}

class OidcTokenResponse(
  val accessToken: AccessToken,
  val tokenType: String,
  val idToken: Option<IdToken> = None,
  val refreshToken: Option<RefreshToken> = None,
  val expiresIn: Option<Duration> = None,
  val scopes: Option<Set<String>> = None
) {
  override fun toString(): String =
    "OidcTokenResponse(accessToken=$accessToken, tokenType=$tokenType, idToken=$idToken, " +
      "refreshToken=$refreshToken, expiresIn=$expiresIn, scopes=$scopes)"
}

/** Preserves the provider's error details and response for test diagnostics. */
class OidcTokenEndpointException internal constructor(
  val httpStatus: Int,
  val oauthError: String,
  val errorDescription: Option<String> = None,
  val responseBody: String = "",
  cause: Throwable? = null
) : RuntimeException("OIDC token request failed (HTTP $httpStatus, $oauthError); response: $responseBody", cause)

open class OidcOperationException internal constructor(operation: String, guidance: String, cause: Throwable? = null) :
  RuntimeException("OIDC $operation failed; $guidance", cause)

/** Identifies the invalid configuration field and explains how to correct it. */
class OidcConfigurationException internal constructor(val field: String, guidance: String, cause: Throwable? = null) :
  OidcOperationException("configuration", "$field: $guidance", cause)
