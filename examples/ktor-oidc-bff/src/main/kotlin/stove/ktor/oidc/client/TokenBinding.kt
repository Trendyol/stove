package stove.ktor.oidc.client

import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.*
import io.ktor.client.request.*
import io.ktor.http.*
import java.security.MessageDigest
import java.time.Clock
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/** One binding belongs to one login/session, including every rotated refresh token. */
sealed interface TokenBinding {
  val tokenType: String
  fun tokenRequest(request: HttpRequestBuilder, endpoint: String)
  fun resourceRequest(request: HttpRequestBuilder, endpoint: String, accessToken: String)
  fun rememberNonce(endpoint: String, nonce: String)

  data object Bearer : TokenBinding {
    override val tokenType = "Bearer"
    override fun tokenRequest(request: HttpRequestBuilder, endpoint: String) = Unit
    override fun resourceRequest(request: HttpRequestBuilder, endpoint: String, accessToken: String) = request.bearerAuth(accessToken)
    override fun rememberNonce(endpoint: String, nonce: String) = Unit
  }

  class Dpop private constructor(private val key: ECKey, private val clock: Clock) : TokenBinding {
    constructor(clock: Clock = Clock.systemUTC()) : this(ECKeyGenerator(Curve.P_256).generate(), clock)

    fun privateJwk(): String = key.toJSONString()

    companion object {
      fun fromPrivateJwk(encoded: String, clock: Clock = Clock.systemUTC()): Dpop {
        val key = ECKey.parse(encoded)
        require(key.curve == Curve.P_256 && key.isPrivate) { "DPoP requires a private P-256 key" }
        return Dpop(key, clock)
      }
    }
    private val nonces = ConcurrentHashMap<String, String>()
    override val tokenType = "DPoP"

    override fun tokenRequest(request: HttpRequestBuilder, endpoint: String) {
      request.headers["DPoP"] = proof(request.method.value, endpoint)
    }

    override fun resourceRequest(request: HttpRequestBuilder, endpoint: String, accessToken: String) {
      request.headers[HttpHeaders.Authorization] = "DPoP $accessToken"
      request.headers["DPoP"] = proof(request.method.value, endpoint, accessToken)
    }

    override fun rememberNonce(endpoint: String, nonce: String) {
      if (nonce.isNotBlank()) nonces[target(endpoint)] = nonce
    }

    fun proof(method: String, endpoint: String, accessToken: String = ""): String {
      val uri = target(endpoint)
      val claims = JWTClaimsSet.Builder()
        .jwtID(UUID.randomUUID().toString())
        .issueTime(Date.from(clock.instant()))
        .claim("htm", method)
        .claim("htu", uri)
      nonces[uri]?.let { claims.claim("nonce", it) }
      if (accessToken.isNotEmpty()) {
        claims.claim(
          "ath",
          Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(accessToken.toByteArray(Charsets.US_ASCII))
          )
        )
      }
      val header = JWSHeader.Builder(JWSAlgorithm.ES256)
        .type(JOSEObjectType("dpop+jwt"))
        .jwk(key.toPublicJWK())
        .build()
      return SignedJWT(header, claims.build()).apply { sign(ECDSASigner(key)) }.serialize()
    }

    private fun target(endpoint: String): String {
      val uri = URLBuilder(endpoint).apply {
        if (protocol == URLProtocol.WS) protocol = URLProtocol.HTTP
        if (protocol == URLProtocol.WSS) protocol = URLProtocol.HTTPS
      }.build()
      return "${uri.protocolWithAuthority}${uri.encodedPath.ifEmpty { "/" }}"
    }
  }
}
