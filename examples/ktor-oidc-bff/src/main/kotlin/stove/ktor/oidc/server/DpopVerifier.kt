package stove.ktor.oidc.server

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.SignedJWT
import java.security.MessageDigest
import java.text.ParseException
import java.time.Clock
import java.util.Base64

/** Validates resource proofs independently of the BFF's proof generator. */
class DpopVerifier(
  private val clock: Clock = Clock.systemUTC(),
  private val replay: ProofReplayStore = ProofReplayCache(clock)
) {
  suspend fun verify(value: String, method: String, target: String, accessToken: String, thumbprint: String) {
    try {
      val proof = SignedJWT.parse(value)
      requireProof(proof.header.type?.toString() == "dpop+jwt", "Expected dpop+jwt")
      requireProof(proof.header.algorithm == JWSAlgorithm.ES256, "Expected ES256")
      requireProof(proof.header.criticalParams.isNullOrEmpty(), "Unsupported critical header")
      val key = proof.header.jwk as? ECKey ?: throw invalidProof("Missing EC public key")
      requireProof(key.curve == Curve.P_256 && !key.isPrivate, "Expected a public P-256 key")
      requireProof(proof.verify(ECDSAVerifier(key)), "Invalid proof signature")
      requireProof(key.computeThumbprint().toString() == thumbprint, "Proof key does not match access token")
      val claims = proof.jwtClaimsSet
      requireProof(claims.getStringClaim("htm") == method, "Wrong HTTP method")
      requireProof(claims.getStringClaim("htu") == target, "Wrong target URL")
      val hash = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(accessToken.toByteArray(Charsets.US_ASCII)))
      requireProof(claims.getStringClaim("ath") == hash, "Wrong access-token hash")
      val issuedAt = claims.issueTime?.toInstant() ?: throw invalidProof("Missing issue time")
      val now = clock.instant()
      requireProof(issuedAt.isAfter(now.minusSeconds(60)) && !issuedAt.isAfter(now.plusSeconds(5)), "Proof outside validity window")
      val id = claims.jwtid.orEmpty()
      requireProof(id.isNotBlank() && id.length <= 200, "Missing or oversized proof ID")
      // Claim the ID only after every cryptographic and request check succeeds.
      replay.accept(thumbprint, id, issuedAt.plusSeconds(60))
    } catch (error: ParseException) {
      throw ResourceUnauthorized("invalid_dpop_proof", "Malformed proof", error)
    } catch (error: JOSEException) {
      throw ResourceUnauthorized("invalid_dpop_proof", "Cannot verify proof", error)
    } catch (error: IllegalArgumentException) {
      throw ResourceUnauthorized("invalid_dpop_proof", "Invalid proof key", error)
    }
  }
}

private fun requireProof(condition: Boolean, reason: String) {
  if (!condition) throw invalidProof(reason)
}

internal fun invalidProof(reason: String) = ResourceUnauthorized("invalid_dpop_proof", reason)
