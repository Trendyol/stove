package stove.ktor.bff.tests

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jwt.SignedJWT
import com.trendyol.stove.http.*
import com.trendyol.stove.oidc.*
import com.trendyol.stove.system.ValidationDsl
import com.trendyol.stove.system.stove
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.*
import stove.ktor.bff.OrdersApi
import stove.ktor.bff.StoveConfig
import stove.ktor.bff.fixtures.DpopProofFixture
import stove.ktor.bff.fixtures.dpopHeaders
import stove.ktor.bff.fixtures.expectedOrders
import stove.ktor.bff.fixtures.header
import stove.ktor.bff.fixtures.withDamagedSignature
import stove.ktor.oidc.client.TokenBinding
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

class ResourceApiTest : FunSpec({
  test("a valid bearer token reads orders for its subject") {
    stove {
      val token = ordersToken()
      http(OrdersApi) {
        getResponse<Map<String, Any>>("/orders", headers = mapOf("Authorization" to "Bearer $token")) {
          it.status shouldBe 200
          it.body() shouldBe expectedOrders("alice")
        }
      }
    }
  }

  test("orders reject missing and malformed access tokens") {
    stove {
      apiStatus(emptyMap(), 401)
      apiStatus(mapOf("Authorization" to "Bearer not-a-jwt"), 401)
    }
  }

  val invalidTokens: Map<String, MockAccessTokenBuilder.() -> Unit> = mapOf(
    "wrong issuer" to { issuer = "https://wrong.example" },
    "wrong audience" to { audience("another-api") },
    "expired token" to { expiresIn = (-5).minutes },
    "future not-before time" to { notBefore = Instant.now().plusSeconds(300) },
    "future issue time" to { issuedAt = Instant.now().plusSeconds(300) },
    "invalid signature" to { invalidSignature = true },
    "empty subject" to { subject = "" },
    "non-string key thumbprint" to { claim("cnf", mapOf("jkt" to 123)) }
  )
  for ((name, configure) in invalidTokens) {
    test("orders reject an access token with $name") {
      stove { apiStatus(mapOf("Authorization" to "Bearer ${ordersToken(configure)}"), 401) }
    }
  }

  test("a DPoP-bound token requires a proof even when bearer tokens are enabled") {
    stove {
      val token = boundOrdersToken(TokenBinding.Dpop())
      apiStatus(mapOf("Authorization" to "Bearer $token"), 401)
      apiStatus(mapOf("Authorization" to "DPoP $token"), 401)
    }
  }

  test("resource proofs bind the signing key, method, target and access token") {
    stove {
      val binding = TokenBinding.Dpop()
      val token = boundOrdersToken(binding)
      val proofs = mapOf(
        "malformed JWT" to "not-a-proof",
        "wrong signing key" to TokenBinding.Dpop().proof("GET", ordersTarget, token),
        "wrong method" to binding.proof("POST", ordersTarget, token),
        "wrong path" to binding.proof("GET", "${StoveConfig.resourceOrigin}/another-path", token),
        "wrong origin" to binding.proof("GET", "https://another.example/orders", token),
        "wrong token hash" to binding.proof("GET", ordersTarget, "different-token"),
        "missing token hash" to binding.proof("GET", ordersTarget)
      )
      for ((name, proof) in proofs) {
        withClue(name) { apiStatus(dpopHeaders(token, proof), 401) }
      }
    }
  }

  test("an invalid signature does not consume the valid proof's replay ID") {
    stove {
      val binding = TokenBinding.Dpop()
      val token = boundOrdersToken(binding)
      val proof = binding.proof("GET", ordersTarget, token)
      apiStatus(dpopHeaders(token, proof.withDamagedSignature()), 401)
      apiStatus(dpopHeaders(token, proof), 200)
    }
  }

  for ((name, offset) in mapOf("expired" to -120L, "future" to 120L)) {
    test("resource API rejects $name proofs") {
      stove {
        val binding = TokenBinding.Dpop(Clock.offset(Clock.systemUTC(), Duration.ofSeconds(offset)))
        val token = boundOrdersToken(binding)
        apiStatus(dpopHeaders(token, binding.proof("GET", ordersTarget, token)), 401)
      }
    }
  }

  test("proof target excludes query parameters") {
    stove {
      val binding = TokenBinding.Dpop()
      val token = boundOrdersToken(binding)
      http(OrdersApi) {
        getBodilessResponse(
          "/orders",
          queryParams = mapOf("page" to "1"),
          headers = dpopHeaders(token, binding.proof("GET", "$ordersTarget?page=1", token))
        ) { it.status shouldBe 200 }
      }
    }
  }

  test("resource proofs require the correct JOSE header and all mandatory claims") {
    stove {
      val signer = DpopProofFixture(ordersTarget)
      val token = ordersToken { claim("cnf", mapOf("jkt" to signer.thumbprint)) }
      val malformed = mapOf(
        "wrong type" to signer.proof(token, header = { type(JOSEObjectType.JWT) }),
        "private JWK" to signer.proofWithPrivateJwk(token),
        "unsupported critical header" to signer.proof(token, header = {
          criticalParams(setOf("unsupported"))
          customParam("unsupported", true)
        }),
        "missing issue time" to signer.proof(token, claims = { issueTime(null) }),
        "missing replay ID" to signer.proof(token, claims = { jwtID(null) }),
        "non-string method" to signer.proof(token, claims = { claim("htm", 123) }),
        "missing target" to signer.proof(token, claims = { claim("htu", null) }),
        "missing token hash" to signer.proof(token, claims = { claim("ath", null) })
      )
      for ((name, proof) in malformed) {
        withClue(name) { apiStatus(dpopHeaders(token, proof), 401) }
      }
      apiStatus(dpopHeaders(token, signer.proof(token)), 200)
    }
  }

  test("concurrent use of the same proof succeeds exactly once, then a fresh proof succeeds") {
    stove {
      val binding = TokenBinding.Dpop()
      val token = boundOrdersToken(binding)
      val headers = dpopHeaders(token, binding.proof("GET", ordersTarget, token))
      val statuses = coroutineScope {
        List(8) { async { ordersResponse(headers).status } }.awaitAll()
      }
      statuses shouldContainExactlyInAnyOrder listOf(200, 401, 401, 401, 401, 401, 401, 401)
      apiStatus(dpopHeaders(token, binding.proof("GET", ordersTarget, token)), 200)
    }
  }

  test("valid authentication without orders scope is forbidden for both bearer and DPoP") {
    stove {
      val bearer = ordersToken { scopes("profile") }
      apiStatus(mapOf("Authorization" to "Bearer $bearer"), 403)
      val binding = TokenBinding.Dpop()
      val token = boundOrdersToken(binding) { scopes("profile") }
      http(OrdersApi) {
        getResponse<Map<String, String>>("/orders", headers = dpopHeaders(token, binding.proof("GET", ordersTarget, token))) {
          it.status shouldBe 403
          it.body()["error"] shouldBe "insufficient_scope"
          it.header("WWW-Authenticate") shouldContain "scope=\"orders:read\""
        }
      }
    }
  }
})

private val ordersTarget: String get() = "${StoveConfig.resourceOrigin}/orders"

private suspend fun ValidationDsl.ordersToken(configure: MockAccessTokenBuilder.() -> Unit = {}): String = oidc {
  mock.accessToken {
    subject = "alice"
    audience("orders-api")
    scopes("orders:read")
    configure()
  }.value
}

private suspend fun ValidationDsl.boundOrdersToken(binding: TokenBinding.Dpop, configure: MockAccessTokenBuilder.() -> Unit = {}): String {
  val publicKey = SignedJWT.parse(binding.proof("GET", ordersTarget)).header.jwk
  return ordersToken {
    claim("cnf", mapOf("jkt" to publicKey.computeThumbprint().toString()))
    configure()
  }
}

private suspend fun ValidationDsl.apiStatus(headers: Map<String, String>, expected: Int) {
  http(OrdersApi) { getBodilessResponse("/orders", headers = headers) { it.status shouldBe expected } }
}

private suspend fun ValidationDsl.ordersResponse(headers: Map<String, String>): StoveHttpResponse.Bodiless {
  lateinit var response: StoveHttpResponse.Bodiless
  http(OrdersApi) { getBodilessResponse("/orders", headers = headers) { response = it } }
  return response
}
