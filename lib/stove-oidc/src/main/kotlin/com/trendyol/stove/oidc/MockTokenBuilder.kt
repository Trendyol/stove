package com.trendyol.stove.oidc

import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Claims shared by access tokens, ID tokens and token-endpoint rules. */
@OidcDsl
interface MockTokenClaims {
  var subject: String

  /** A finite lifetime; negative values produce expired tokens for validation tests. */
  var expiresIn: Duration
  var issuedAt: Instant?
  var expiresAt: Instant?
  var notBefore: Instant?
  var jwtId: String?

  /** An explicit issuer override for negative validation tests. */
  var issuer: String?

  /** Replaces the default scopes. */
  fun scopes(vararg values: String)
  fun claim(name: String, value: Any?)
}

/** Configuration for provider defaults and token-endpoint rules. */
@OidcDsl
interface MockTokenBuilder : MockTokenClaims {
  /** Replaces the default audiences. */
  fun audience(vararg values: String)
}

@OidcDsl
interface MockAccessTokenBuilder : MockTokenBuilder {
  /** Sign with an unpublished key for negative signature validation tests. */
  var invalidSignature: Boolean
}

/** The audience is supplied by idToken(clientId), so it cannot be silently overridden here. */
@OidcDsl
interface MockIdTokenBuilder : MockTokenClaims {
  var invalidSignature: Boolean
}

internal class MutableMockToken : MockAccessTokenBuilder, MockIdTokenBuilder {
  override var subject: String = "stove-user"
  override var expiresIn: Duration = 5.minutes
  override var issuedAt: Instant? = null
  override var expiresAt: Instant? = null
  override var notBefore: Instant? = null
  override var jwtId: String? = null
  override var issuer: String? = null
  override var invalidSignature: Boolean = false
  private val audiences = linkedSetOf<String>()
  private val scopes = linkedSetOf<String>()
  private val claims = linkedMapOf<String, Any?>()

  /** Replaces the default audiences. */
  override fun audience(vararg values: String) {
    audiences.clear()
    audiences.addAll(values)
  }

  /** Replaces the default scopes. */
  override fun scopes(vararg values: String) {
    scopes.clear()
    scopes.addAll(values)
  }
  override fun claim(name: String, value: Any?) {
    RESERVED[name]?.let { property ->
      throw OidcConfigurationException("claim", "Use $property to configure this standard claim")
    }
    requireConfiguration(name.isNotBlank(), "claim", "Claim name must not be blank")
    claims[name] = jsonCopy(value)
  }

  internal fun frozenCopy(): MutableMockToken {
    val copy = MutableMockToken()
    copy.subject = subject
    copy.expiresIn = expiresIn
    copy.issuedAt = issuedAt
    copy.expiresAt = expiresAt
    copy.notBefore = notBefore
    copy.jwtId = jwtId
    copy.issuer = issuer
    copy.invalidSignature = invalidSignature
    copy.audiences.addAll(audiences)
    copy.scopes.addAll(scopes)
    claims.forEach { (key, value) -> copy.claims[key] = jsonCopy(value) }
    return copy
  }

  internal fun validate() {
    requireConfiguration(expiresIn.isFinite(), "expiresIn", "Token lifetime must be finite; negative values are allowed for expired tokens")
  }

  internal fun build(issuerUrl: String, now: Instant): MockTokenSpec {
    validate()
    val iat = issuedAt ?: now
    val result = linkedMapOf<String, Any>(
      "iss" to (issuer ?: issuerUrl),
      "sub" to subject,
      "aud" to audiences.toList(),
      "iat" to Date.from(iat),
      "nbf" to Date.from(notBefore ?: iat),
      "exp" to Date.from(expiresAt ?: iat.plusMillis(expiresIn.inWholeMilliseconds)),
      "jti" to (jwtId ?: UUID.randomUUID().toString())
    )
    if (scopes.isNotEmpty()) result["scope"] = scopes.joinToString(" ")
    // Null is a valid nested JSON value; a null top-level claim means omission.
    claims.forEach { (key, value) -> if (value != null) result[key] = value }
    return MockTokenSpec(result.toMap(), invalidSignature)
  }

  private fun jsonCopy(value: Any?): Any? = when (value) {
    null, is String, is Boolean, is Byte, is Short, is Int, is Long, is java.math.BigInteger, is java.math.BigDecimal -> value

    is Double -> value.also { requireConfiguration(it.isFinite(), "claim", "Claim numbers must be finite") }

    is Float -> value.also { requireConfiguration(it.isFinite(), "claim", "Claim numbers must be finite") }

    is List<*> -> value.map(::jsonCopy)

    is Map<*, *> -> value.entries.associate { (k, v) ->
      if (k !is String) throw OidcConfigurationException("claim", "Claim object keys must be strings")
      k to jsonCopy(v)
    }

    else -> throw OidcConfigurationException("claim", "Claim value must be JSON-compatible")
  }

  companion object {
    private val RESERVED = mapOf(
      "iss" to "issuer", "sub" to "subject", "aud" to "audience (or idToken clientId)",
      "iat" to "issuedAt", "exp" to "expiresAt", "nbf" to "notBefore", "jti" to "jwtId",
      "scope" to "scopes", "nonce" to "the idToken nonce argument"
    )
  }
}

internal class MockTokenSpec(val claims: Map<String, Any>, val invalidSignature: Boolean)
