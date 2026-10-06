package stove.ktor.bff.tests

import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import stove.ktor.oidc.client.*

class OidcAuthenticationTest : FunSpec({
  test("resource proofs follow the actual method and URL and resolve tokens once across a nonce retry") {
    val proofs = mutableListOf<SignedJWT>()
    var tokens = 0
    HttpClient(
      MockEngine { request ->
        request.method shouldBe HttpMethod.Patch
        request.headers[HttpHeaders.Authorization] shouldBe "DPoP access"
        proofs += SignedJWT.parse(request.headers["DPoP"])
        if (proofs.size == 1) {
          respond("{\"error\":\"use_dpop_nonce\"}", HttpStatusCode.Unauthorized, headersOf("DPoP-Nonce", "challenge"))
        } else {
          respond("updated")
        }
      }
    ) { install(OidcAuthentication) }.use { http ->
      http.patch("https://api.example/documents/42?revision=2") {
        oidcResource(TokenBinding.Dpop()) {
          tokens++
          "access"
        }
        setBody("document")
      }.bodyAsText() shouldBe "updated"
    }
    tokens shouldBe 1
    proofs.size shouldBe 2
    proofs.forEach {
      it.jwtClaimsSet.getStringClaim("htm") shouldBe "PATCH"
      it.jwtClaimsSet.getStringClaim("htu") shouldBe "https://api.example/documents/42"
      it.jwtClaimsSet.getStringClaim("ath").isNotBlank() shouldBe true
    }
    proofs[1].jwtClaimsSet.getStringClaim("nonce") shouldBe "challenge"
    proofs[1].jwtClaimsSet.jwtid shouldNotBe proofs[0].jwtClaimsSet.jwtid
  }

  test("shared clients isolate concurrent bearer and DPoP requests and leave public requests untouched") {
    HttpClient(
      MockEngine { request ->
        when (request.url.encodedPath) {
          "/alice" -> {
            request.headers[HttpHeaders.Authorization] shouldBe "DPoP alice"
            SignedJWT.parse(request.headers["DPoP"]).jwtClaimsSet.getStringClaim("htm") shouldBe "DELETE"
          }

          "/bob" -> {
            request.headers[HttpHeaders.Authorization] shouldBe "Bearer bob"
            request.headers["DPoP"] shouldBe null
          }

          else -> {
            request.headers[HttpHeaders.Authorization] shouldBe null
            request.headers["DPoP"] shouldBe null
          }
        }
        respond("ok")
      }
    ) { install(OidcAuthentication) }.use { http ->
      coroutineScope {
        listOf(
          async { http.delete("https://api.example/alice") { oidcResource(TokenBinding.Dpop()) { "alice" } } },
          async { http.get("https://api.example/bob") { oidcResource(TokenBinding.Bearer) { "bob" } } },
          async { http.get("https://api.example/public") }
        ).awaitAll()
      }
    }
  }

  test("same-origin redirects receive fresh proofs for the redirected URL") {
    val proofs = mutableListOf<SignedJWT>()
    HttpClient(
      MockEngine { request ->
        proofs += SignedJWT.parse(request.headers["DPoP"])
        if (proofs.size == 1) {
          respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/final"))
        } else {
          respond("done")
        }
      }
    ) { install(OidcAuthentication) }.use { http ->
      http.get("https://api.example/start") { oidcResource(TokenBinding.Dpop()) { "access" } }.bodyAsText() shouldBe "done"
    }
    proofs.size shouldBe 2
    proofs[1].jwtClaimsSet.getStringClaim("htu") shouldBe "https://api.example/final"
    proofs[1].jwtClaimsSet.jwtid shouldNotBe proofs[0].jwtClaimsSet.jwtid
  }

  for (binding in listOf(TokenBinding.Bearer, TokenBinding.Dpop())) {
    test("${binding.tokenType} credentials never reach a redirected origin") {
      var calls = 0
      HttpClient(
        MockEngine {
          calls++
          respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://untrusted.example/stolen"))
        }
      ) { install(OidcAuthentication) }.use { http ->
        shouldThrow<OidcRedirectRejected> {
          http.get("https://api.example/start") { oidcResource(binding) { "access" } }
        }
      }
      calls shouldBe 1
    }
  }

  test("token grant bodies cannot follow redirects even within the same origin") {
    var calls = 0
    HttpClient(
      MockEngine {
        calls++
        respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "/other"))
      }
    ) {
      install(HttpRedirect) { checkHttpMethod = false }
      install(OidcAuthentication)
    }.use { http ->
      shouldThrow<OidcRedirectRejected> {
        http.submitForm("https://idp.example/token", parametersOf("refresh_token", "secret")) { oidcToken(TokenBinding.Dpop()) }
      }
    }
    calls shouldBe 1
  }

  test("the nonce retry budget survives redirects") {
    var calls = 0
    HttpClient(
      MockEngine { request ->
        calls++
        when {
          calls == 1 -> respond("", HttpStatusCode.Unauthorized, headersOf("DPoP-Nonce" to listOf("first"), HttpHeaders.WWWAuthenticate to listOf("DPoP error=\"use_dpop_nonce\"")))
          request.url.encodedPath == "/start" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/final"))
          else -> respond("", HttpStatusCode.Unauthorized, headersOf("DPoP-Nonce" to listOf("second"), HttpHeaders.WWWAuthenticate to listOf("DPoP error=\"use_dpop_nonce\"")))
        }
      }
    ) { install(OidcAuthentication) }.use { http ->
      http.get("https://api.example/start") { oidcResource(TokenBinding.Dpop()) { "access" } }.status shouldBe HttpStatusCode.Unauthorized
    }
    calls shouldBe 3
  }

  test("form grants replay unchanged on nonce challenge with Ktor success validation enabled") {
    val bodies = mutableListOf<String>()
    HttpClient(
      MockEngine { request ->
        bodies += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        if (bodies.size == 1) {
          respond("{\"error\":\"use_dpop_nonce\"}", HttpStatusCode.BadRequest, headersOf("DPoP-Nonce", "challenge"))
        } else {
          respond("tokens")
        }
      }
    ) {
      install(OidcAuthentication)
      expectSuccess = true
    }.use { http ->
      http.submitForm("https://idp.example/token", parametersOf("refresh_token", "rotating-credential")) {
        oidcToken(TokenBinding.Dpop())
      }.bodyAsText() shouldBe "tokens"
    }
    bodies shouldBe listOf("refresh_token=rotating-credential", "refresh_token=rotating-credential")
  }

  test("nonce challenges do not replay streaming request bodies") {
    var calls = 0
    HttpClient(
      MockEngine {
        calls++
        respond("{\"error\":\"use_dpop_nonce\"}", HttpStatusCode.Unauthorized, headersOf("DPoP-Nonce", "challenge"))
      }
    ) { install(OidcAuthentication) }.use { http ->
      val response = http.post("https://api.example/upload") {
        oidcResource(TokenBinding.Dpop()) { "access" }
        setBody(object : OutgoingContent.ReadChannelContent() {
          override fun readFrom() = ByteReadChannel("stream")
        })
      }
      response.status shouldBe HttpStatusCode.Unauthorized
      response.bodyAsText() shouldBe "{\"error\":\"use_dpop_nonce\"}"
    }
    calls shouldBe 1
  }
})
