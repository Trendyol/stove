package stove.ktor.bff.fixtures

import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jose.util.JSONObjectUtils
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.MessageDigest
import java.util.Date
import java.util.UUID

/** Signs deliberately malformed proofs without changing the BFF's production proof builder. */
class DpopProofFixture(private val target: String) {
  private val key = ECKeyGenerator(Curve.P_256).generate()
  val thumbprint: String = key.computeThumbprint().toString()

  fun proof(
    accessToken: String,
    header: JWSHeader.Builder.() -> Unit = {},
    claims: JWTClaimsSet.Builder.() -> Unit = {}
  ): String {
    val jwt = SignedJWT(
      JWSHeader.Builder(JWSAlgorithm.ES256)
        .type(JOSEObjectType("dpop+jwt"))
        .jwk(key.toPublicJWK())
        .apply(header)
        .build(),
      JWTClaimsSet.Builder()
        .jwtID(UUID.randomUUID().toString())
        .issueTime(Date())
        .claim("htm", "GET")
        .claim("htu", target)
        .claim("ath", Base64URL.encode(MessageDigest.getInstance("SHA-256").digest(accessToken.toByteArray(Charsets.US_ASCII))).toString())
        .apply(claims)
        .build()
    )
    jwt.sign(ECDSASigner(key))
    return jwt.serialize()
  }

  fun proofWithPrivateJwk(accessToken: String): String {
    // Nimbus refuses private JWKs in its builder; encode that malformed header directly.
    val parts = proof(accessToken).split('.')
    val header = JSONObjectUtils.parse(Base64URL(parts[0]).decodeToString())
    header["jwk"] = key.toJSONObject()
    return "${Base64URL.encode(JSONObjectUtils.toJSONString(header))}.${parts[1]}.${parts[2]}"
  }
}

fun String.withDamagedSignature(): String {
  val parts = split('.')
  val signature = (if (parts[2].first() == 'A') "B" else "A") + parts[2].drop(1)
  return "${parts[0]}.${parts[1]}.$signature"
}

fun dpopHeaders(token: String, proof: String): Map<String, String> = mapOf("Authorization" to "DPoP $token", "DPoP" to proof)
