package stove.ktor.bff.tests

import arrow.core.some
import com.trendyol.stove.http.*
import com.trendyol.stove.oidc.*
import com.trendyol.stove.system.ValidationDsl
import com.trendyol.stove.system.stove
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.http.Url
import stove.ktor.bff.IdentityProvider
import stove.ktor.bff.auth.IdTokenVerifier
import stove.ktor.bff.auth.LoginRejected
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

class IdTokenVerifierTest : FunSpec({
  test("a signed ID token is accepted only for its intended client") {
    stove {
      val issuer = oidc { endpoints.issuerUrl }
      val keys = providerKeys()
      val verifier = IdTokenVerifier(issuer, "stove-bff")
      val token = oidc { mock.idToken("stove-bff", "nonce".some()) { subject = "alice" } }
      verifier.verify(token.value, "nonce", keys).subject shouldBe "alice"
      shouldThrow<LoginRejected> { IdTokenVerifier(issuer, "wrong-client").verify(token.value, "nonce", keys) }
      shouldThrow<LoginRejected> { verifier.verify("not-a-jwt", "nonce", keys) }
    }
  }

  test("nonce is required at login and must match whenever present on refresh") {
    stove {
      val verifier = IdTokenVerifier(oidc { endpoints.issuerUrl }, "stove-bff")
      val keys = providerKeys()
      val withNonce = oidc { mock.idToken("stove-bff", "nonce".some()) { subject = "alice" } }
      shouldThrow<LoginRejected> { verifier.verify(withNonce.value, "wrong-nonce", keys) }
      shouldThrow<LoginRejected> { verifier.verify(withNonce.value, "wrong-nonce", keys, nonceRequired = false) }
      val withoutNonce = oidc { mock.idToken("stove-bff") { subject = "alice" } }
      verifier.verify(withoutNonce.value, "nonce", keys, nonceRequired = false).subject shouldBe "alice"
      shouldThrow<LoginRejected> { verifier.verify(withoutNonce.value, "nonce", keys) }
    }
  }

  val invalidTokens: Map<String, MockIdTokenBuilder.() -> Unit> = mapOf(
    "wrong issuer" to { issuer = "https://wrong.example" },
    "expired token" to { expiresIn = (-5).minutes },
    "future not-before time" to { notBefore = Instant.now().plusSeconds(300) },
    "future issue time" to { issuedAt = Instant.now().plusSeconds(300) },
    "invalid signature" to { invalidSignature = true },
    "wrong authorized party" to { claim("azp", "another-client") }
  )
  for ((name, configure) in invalidTokens) {
    test("ID token verification rejects $name") {
      stove {
        val verifier = IdTokenVerifier(oidc { endpoints.issuerUrl }, "stove-bff")
        val keys = providerKeys()
        val token = oidc { mock.idToken("stove-bff", "nonce".some(), configure) }
        shouldThrow<LoginRejected> { verifier.verify(token.value, "nonce", keys) }
      }
    }
  }
})

private suspend fun ValidationDsl.providerKeys(): String {
  val uri = oidc { endpoints.jwksUri }
  lateinit var keys: String
  http(IdentityProvider) {
    getResponse<String>(Url(uri).encodedPath) {
      it.status shouldBe 200
      keys = it.body()
    }
  }
  return keys
}
