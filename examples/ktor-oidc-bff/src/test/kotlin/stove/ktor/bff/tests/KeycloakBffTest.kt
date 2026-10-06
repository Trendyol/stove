package stove.ktor.bff.tests

import arrow.core.None
import arrow.core.getOrElse
import arrow.core.some
import com.nimbusds.jwt.SignedJWT
import com.trendyol.stove.http.*
import com.trendyol.stove.oidc.oidc
import com.trendyol.stove.system.ValidationDsl
import com.trendyol.stove.system.stove
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.*
import kotlinx.coroutines.*
import stove.ktor.bff.IdentityProvider
import stove.ktor.bff.OrdersApi
import stove.ktor.bff.StoveConfig
import stove.ktor.bff.auth.LoginAttempt
import stove.ktor.bff.fixtures.*
import stove.ktor.oidc.client.TokenBinding

class KeycloakBffTest : FunSpec({
  test("BFF shares DPoP refreshes across profile and orders requests, then logs out") {
    stove {
      val browser = BrowserFlow(this)
      val cookie = browser.signIn()
      val session = browser.session(cookie)
      http { shouldReturnOrders(cookie, session.subject) }
      // The realm's access lifetime is four seconds; cross two rotations with the same cookie.
      repeat(2) {
        delay(4500)
        assertConcurrentProfileAndOrders(cookie, session.subject)
      }
      http {
        postAndExpectBodilessResponse(
          "/auth/logout",
          None,
          headers = mapOf("Cookie" to cookie, "Origin" to StoveConfig.origin, "X-CSRF-Token" to session.csrfToken)
        ) { it.status shouldBe 204 }
        getBodilessResponse("/api/profile", headers = mapOf("Cookie" to cookie)) { it.status shouldBe 401 }
        getBodilessResponse("/api/orders", headers = mapOf("Cookie" to cookie)) { it.status shouldBe 401 }
      }
    }
  }

  test("Keycloak requires DPoP at code exchange and rejects missing or mismatched resource proofs") {
    stove {
      val binding = TokenBinding.Dpop()
      val endpoint = oidc { endpoints.tokenEndpoint }
      val userInfo = oidc { endpoints.userInfoEndpoint.getOrElse { error("Missing UserInfo endpoint") } }
      val grant = authorizationGrant()
      postToken(endpoint, grant, emptyMap(), 400)
      val response = postToken(endpoint, authorizationGrant(), mapOf("DPoP" to binding.proof("POST", endpoint)))
      response.getValue("token_type") shouldBe "DPoP"
      val access = response.getValue("access_token")
      val publicKey = SignedJWT.parse(binding.proof("POST", endpoint)).header.jwk
      SignedJWT.parse(access).jwtClaimsSet.getJSONObjectClaim("cnf")["jkt"] shouldBe publicKey.computeThumbprint().toString()
      http(IdentityProvider) {
        getBodilessResponse(Url(userInfo).encodedPath, headers = mapOf("Authorization" to "Bearer $access")) { it.status shouldBe 401 }
        getBodilessResponse(Url(userInfo).encodedPath, headers = mapOf("Authorization" to "DPoP $access")) { it.status shouldBe 401 }
        getBodilessResponse(Url(userInfo).encodedPath, headers = dpopHeaders(access, TokenBinding.Dpop().proof("GET", userInfo, access))) { it.status shouldBe 401 }
        getBodilessResponse(Url(userInfo).encodedPath, headers = dpopHeaders(access, binding.proof("GET", userInfo, "different-access-token"))) { it.status shouldBe 401 }
        getBodilessResponse(Url(userInfo).encodedPath, headers = dpopHeaders(access, binding.proof("GET", userInfo, access))) { it.status shouldBe 200 }
      }
      val orders = "${StoveConfig.resourceOrigin}/orders"
      val proof = binding.proof("GET", orders, access)
      http(OrdersApi) {
        getBodilessResponse("/orders", headers = mapOf("Authorization" to "Bearer $access")) { it.status shouldBe 401 }
        getBodilessResponse("/orders", headers = dpopHeaders(access, binding.proof("GET", userInfo, access))) { it.status shouldBe 401 }
        val headers = dpopHeaders(access, proof)
        getBodilessResponse("/orders", headers = headers) { it.status shouldBe 200 }
        getBodilessResponse("/orders", headers = headers) { it.status shouldBe 401 }
      }
    }
  }

  test("Keycloak login without orders scope returns forbidden without ending the browser session") {
    stove {
      val cookie = BrowserFlow(this).signIn { it.withParameter("scope", "openid profile email") }
      http {
        getBodilessResponse("/api/orders", headers = mapOf("Cookie" to cookie)) { it.status shouldBe 403 }
        getBodilessResponse("/api/session", headers = mapOf("Cookie" to cookie)) { it.status shouldBe 200 }
        getBodilessResponse("/api/profile", headers = mapOf("Cookie" to cookie)) { it.status shouldBe 200 }
      }
    }
  }

  test("Keycloak requires client authentication for refresh and rejects reuse after rotation") {
    stove {
      val binding = TokenBinding.Dpop()
      val endpoint = oidc { endpoints.tokenEndpoint }
      val initial = postToken(endpoint, authorizationGrant(), mapOf("DPoP" to binding.proof("POST", endpoint)))
      val refresh = parameters {
        append("grant_type", "refresh_token")
        append("refresh_token", initial.getValue("refresh_token"))
      }
      postToken(endpoint, refresh, mapOf("DPoP" to binding.proof("POST", endpoint)), 401, secret = "wrong-secret")
      val rotated = postToken(endpoint, refresh, mapOf("DPoP" to binding.proof("POST", endpoint)))
      rotated.getValue("refresh_token") shouldNotBe initial.getValue("refresh_token")
      rotated.getValue("token_type") shouldBe "DPoP"
      SignedJWT.parse(rotated.getValue("access_token")).jwtClaimsSet.getJSONObjectClaim("cnf") shouldBe
        SignedJWT.parse(initial.getValue("access_token")).jwtClaimsSet.getJSONObjectClaim("cnf")
      postToken(endpoint, refresh, mapOf("DPoP" to binding.proof("POST", endpoint)), 400)
    }
  }
})

private suspend fun ValidationDsl.assertConcurrentProfileAndOrders(cookie: String, subject: String) = coroutineScope {
  repeat(2) {
    launch {
      http {
        getResponse<Map<String, String>>("/api/profile", headers = mapOf("Cookie" to cookie)) {
          it.status shouldBe 200
          it.body()["sub"] shouldBe subject
        }
      }
    }
    launch { http { shouldReturnOrders(cookie, subject) } }
  }
}

private suspend fun ValidationDsl.authorizationGrant(): Parameters {
  val endpoint = oidc { endpoints.authorizationEndpoint }
  val attempt = LoginAttempt("test-state", "test-nonce", "v".repeat(43))
  val url = URLBuilder(endpoint).apply {
    parameters.appendAll(
      parametersOf(
        "client_id" to listOf("stove-bff"),
        "response_type" to listOf("code"),
        "redirect_uri" to listOf("${StoveConfig.origin}/auth/callback"),
        "scope" to listOf("openid profile email orders:read"),
        "state" to listOf(attempt.state),
        "nonce" to listOf(attempt.nonce),
        "code_challenge" to listOf(attempt.challenge),
        "code_challenge_method" to listOf("S256")
      )
    )
  }.build()
  val callback = BrowserFlow(this).authorize(LoginRedirect(url, ""))
  return parameters {
    append("grant_type", "authorization_code")
    append("code", callback.query().getValue("code"))
    append("redirect_uri", "${StoveConfig.origin}/auth/callback")
    append("code_verifier", attempt.verifier)
  }
}

private suspend fun ValidationDsl.postToken(
  endpoint: String,
  grant: Parameters,
  headers: Map<String, String>,
  expectedStatus: Int = 200,
  secret: String = "bff-example-secret"
): Map<String, String> {
  lateinit var result: Map<String, String>
  val body = FormDataContent(
    parameters {
      appendAll(grant)
      append("client_id", "stove-bff")
      append("client_secret", secret)
    }
  )
  http(IdentityProvider) {
    postAndExpectBody<Map<String, String>>(Url(endpoint).encodedPath, body.some(), headers) {
      it.status shouldBe expectedStatus
      result = it.body()
    }
  }
  return result
}
