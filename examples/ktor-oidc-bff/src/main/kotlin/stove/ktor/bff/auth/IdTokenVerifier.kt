package stove.ktor.bff.auth

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import java.text.ParseException
import java.time.Instant

data class VerifiedIdentity(val subject: String, val expiresAt: Instant)

/** This example deliberately supports only RS256; the provider must be configured accordingly. */
class IdTokenVerifier(private val issuer: String, private val clientId: String) {
  fun verify(value: String, nonce: String, jwks: String, nonceRequired: Boolean = true): VerifiedIdentity = try {
    val jwt = SignedJWT.parse(value)
    if (jwt.header.algorithm != JWSAlgorithm.RS256) throw LoginRejected("Expected an RS256 ID token")
    val key = JWKSet.parse(jwks).getKeyByKeyId(jwt.header.keyID) as? RSAKey
      ?: throw LoginRejected("Unknown RSA signing key")
    if (!jwt.verify(RSASSAVerifier(key))) throw LoginRejected("Invalid ID token signature")

    val claims = jwt.jwtClaimsSet
    val now = Instant.now()
    val expiry = claims.expirationTime?.toInstant() ?: throw LoginRejected("ID token has no expiry")
    val issuedAt = claims.issueTime?.toInstant() ?: throw LoginRejected("ID token has no issue time")
    val subject = claims.subject ?: throw LoginRejected("ID token has no subject")
    val authorizedParty = claims.getStringClaim("azp")
    if (claims.issuer != issuer || clientId !in claims.audience) throw LoginRejected("Unexpected issuer or audience")
    if ((claims.audience.size > 1 || authorizedParty != null) && authorizedParty != clientId) {
      throw LoginRejected("Unexpected authorized party")
    }
    val receivedNonce = claims.getStringClaim("nonce")
    if (subject.isBlank() || ((nonceRequired || receivedNonce != null) && receivedNonce != nonce)) {
      throw LoginRejected("Invalid subject or nonce")
    }
    if (!expiry.isAfter(now) || issuedAt.isAfter(now.plusSeconds(30)) || claims.notBeforeTime?.toInstant()?.isAfter(now) == true) {
      throw LoginRejected("ID token is outside its validity period")
    }
    VerifiedIdentity(subject, expiry)
  } catch (error: ParseException) {
    throw LoginRejected("Malformed ID token or signing keys", error)
  } catch (error: JOSEException) {
    throw LoginRejected("Cannot verify ID token", error)
  }
}
