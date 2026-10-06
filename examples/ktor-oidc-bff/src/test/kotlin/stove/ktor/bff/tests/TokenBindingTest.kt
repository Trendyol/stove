package stove.ktor.bff.tests

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.JsonPrimitive
import stove.ktor.bff.auth.OidcEndpointRejected
import stove.ktor.bff.auth.providerRequest
import stove.ktor.oidc.client.*
import java.security.MessageDigest
import java.time.*
import java.util.Base64

class TokenBindingTest : FunSpec({
  test("WebSocket proofs use the HTTP handshake URI and share its nonce") {
    val binding = TokenBinding.Dpop()
    binding.rememberNonce("wss://api.example/socket?ignored=yes", "challenge")
    val proof = SignedJWT.parse(binding.proof("GET", "https://api.example/socket", "access"))
    proof.jwtClaimsSet.getStringClaim("htu") shouldBe "https://api.example/socket"
    proof.jwtClaimsSet.getStringClaim("nonce") shouldBe "challenge"
    SignedJWT.parse(binding.proof("GET", "ws://api.example:8080/socket?query=yes", "access"))
      .jwtClaimsSet.getStringClaim("htu") shouldBe "http://api.example:8080/socket"
  }

  test("proofs verify with a public P-256 key and bind method, target, time and access-token hash") {
    val now = Instant.parse("2026-01-01T00:00:00Z")
    val binding = TokenBinding.Dpop(Clock.fixed(now, ZoneOffset.UTC))
    val proof = SignedJWT.parse(binding.proof("GET", "https://idp.example/userinfo?ignored=yes#fragment", "access"))
    proof.header.algorithm shouldBe JWSAlgorithm.ES256
    proof.header.type.toString() shouldBe "dpop+jwt"
    proof.header.jwk.isPrivate shouldBe false
    proof.verify(ECDSAVerifier(proof.header.jwk.toECKey())) shouldBe true
    proof.jwtClaimsSet.issueTime.toInstant() shouldBe now
    proof.jwtClaimsSet.getStringClaim("htm") shouldBe "GET"
    proof.jwtClaimsSet.getStringClaim("htu") shouldBe "https://idp.example/userinfo"
    proof.jwtClaimsSet.getStringClaim("ath") shouldBe Base64.getUrlEncoder().withoutPadding()
      .encodeToString(MessageDigest.getInstance("SHA-256").digest("access".toByteArray()))
    val next = SignedJWT.parse(binding.proof("POST", "https://idp.example/token"))
    next.jwtClaimsSet.jwtid shouldNotBe proof.jwtClaimsSet.jwtid
    next.header.jwk shouldBe proof.header.jwk
    next.jwtClaimsSet.getStringClaim("ath") shouldBe null
    SignedJWT.parse(TokenBinding.Dpop().proof("POST", "https://idp.example/token")).header.jwk shouldNotBe proof.header.jwk
  }

  test("token and resource nonce challenges retry once with a fresh proof and preserve separate nonces") {
    val binding = TokenBinding.Dpop()
    for (endpoint in listOf("https://idp.example/token", "https://idp.example/userinfo")) {
      val proofs = mutableListOf<SignedJWT>()
      HttpClient(
        MockEngine { request ->
          proofs += SignedJWT.parse(request.headers["DPoP"])
          if (proofs.size == 1) {
            respond("", HttpStatusCode.Unauthorized, headersOf("DPoP-Nonce" to listOf(endpoint), "WWW-Authenticate" to listOf("DPoP error=\"use_dpop_nonce\"")))
          } else {
            respond("{\"ok\":true}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
          }
        }
      ) { install(OidcAuthentication) }.use { http ->
        providerRequest { http.post(endpoint) { oidcToken(binding) } }["ok"] shouldBe JsonPrimitive(true)
      }
      withClue(endpoint) {
        proofs.size shouldBe 2
        proofs[0].jwtClaimsSet.getStringClaim("nonce") shouldBe null
        proofs[1].jwtClaimsSet.getStringClaim("nonce") shouldBe endpoint
        proofs[0].jwtClaimsSet.jwtid shouldNotBe proofs[1].jwtClaimsSet.jwtid
      }
    }
  }

  for ((error, expectedCalls) in mapOf("use_dpop_nonce" to 2, "invalid_grant" to 1)) {
    test("$error stops after $expectedCalls provider calls") {
      var calls = 0
      val binding = TokenBinding.Dpop()
      val endpoint = "https://idp.example/token"
      HttpClient(
        MockEngine {
          calls++
          respond("{\"error\":\"$error\"}", HttpStatusCode.BadRequest, headersOf("DPoP-Nonce", "nonce"))
        }
      ) { install(OidcAuthentication) }.use { http ->
        shouldThrow<OidcEndpointRejected> {
          providerRequest { http.post(endpoint) { oidcToken(binding) } }
        }
      }
      calls shouldBe expectedCalls
    }
  }
})
