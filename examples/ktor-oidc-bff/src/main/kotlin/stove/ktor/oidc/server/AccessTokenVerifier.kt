package stove.ktor.oidc.server

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import java.text.ParseException
import java.time.Clock

sealed interface AccessBinding {
  data object Bearer : AccessBinding
  data class Dpop(val thumbprint: String) : AccessBinding
}

data class VerifiedAccessToken(val subject: String, val scopes: Set<String>, val binding: AccessBinding)

/** Trust comes from the configured issuer's JWKS, never from an access-token header. */
class AccessTokenVerifier(
  private val issuer: String,
  private val audience: String,
  private val keys: JWKSet,
  private val clock: Clock = Clock.systemUTC()
) {
  fun verify(value: String): VerifiedAccessToken = try {
    val token = SignedJWT.parse(value)
    requireToken(token.header.algorithm == JWSAlgorithm.RS256, "Expected RS256")
    requireToken(token.header.criticalParams.isNullOrEmpty(), "Unsupported critical header")
    val key = keys.getKeyByKeyId(token.header.keyID) as? RSAKey ?: throw ResourceUnauthorized("invalid_token", "Unknown signing key")
    requireToken(token.verify(RSASSAVerifier(key)), "Invalid signature")
    val claims = token.jwtClaimsSet
    val now = clock.instant()
    val expiry = claims.expirationTime?.toInstant() ?: throw ResourceUnauthorized("invalid_token", "Missing expiry")
    val issuedAt = claims.issueTime?.toInstant() ?: throw ResourceUnauthorized("invalid_token", "Missing issue time")
    val subject = claims.subject.orEmpty()
    requireToken(claims.issuer == issuer && audience in claims.audience, "Wrong issuer or audience")
    requireToken(subject.isNotBlank(), "Missing subject")
    requireToken(expiry.isAfter(now) && !issuedAt.isAfter(now.plusSeconds(30)), "Token outside validity period")
    requireToken(claims.notBeforeTime?.toInstant()?.isAfter(now) != true, "Token not yet valid")
    val scopes = claims.getStringClaim("scope").orEmpty().split(' ').filter(String::isNotBlank).toSet()
    val confirmation = claims.getJSONObjectClaim("cnf")
    val binding = if (confirmation == null) {
      AccessBinding.Bearer
    } else {
      val thumbprint = confirmation["jkt"] as? String ?: throw ResourceUnauthorized("invalid_token", "Missing key thumbprint")
      requireToken(thumbprint.isNotBlank(), "Empty key thumbprint")
      AccessBinding.Dpop(thumbprint)
    }
    VerifiedAccessToken(subject, scopes, binding)
  } catch (error: ParseException) {
    throw ResourceUnauthorized("invalid_token", "Malformed access token", error)
  } catch (error: JOSEException) {
    throw ResourceUnauthorized("invalid_token", "Cannot verify access token", error)
  }
}

private fun requireToken(condition: Boolean, reason: String) {
  if (!condition) throw ResourceUnauthorized("invalid_token", reason)
}

class ResourceUnauthorized(val error: String, val reason: String, cause: Throwable? = null) : RuntimeException(reason, cause)
