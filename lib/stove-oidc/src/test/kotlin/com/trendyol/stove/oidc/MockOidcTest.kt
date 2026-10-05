package com.trendyol.stove.oidc

import arrow.core.getOrElse
import arrow.core.some
import com.nimbusds.jwt.SignedJWT
import com.trendyol.stove.reporting.JsonReportRenderer
import com.trendyol.stove.reporting.StoveTestContext
import com.trendyol.stove.system.*
import com.trendyol.stove.system.abstractions.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes

class MockOidcTest : FunSpec({
  test("keyed issuers expose configuration before application start and own separate signing keys") {
    val stove = Stove()
    try {
      stove.with {
        oidc { OidcSystemOptions(configureExposedConfiguration = { listOf("issuer=${it.issuerUrl}") }) }
        oidc(Partner) { OidcSystemOptions(OidcProvider.Mock("stove")) }
      }.applicationUnderTest(object : ApplicationUnderTest<Unit> {
        override suspend fun start(configurations: List<String>) {
          check(configurations.single().startsWith("issuer=http"))
        }
        override suspend fun stop() = Unit
      })
      stove.run()
      val first = stove.getOrNone<OidcSystem>().getOrElse { error("missing") }
      val second = stove.getOrNone<OidcSystem>(Partner).getOrElse { error("missing") }
      val configurations = first.configuration()
      configurations.single() shouldBe "issuer=${first.endpoints.issuerUrl}"
      (first.endpoints.issuerUrl != second.endpoints.issuerUrl) shouldBe true
      val firstKeys = com.nimbusds.jose.jwk.JWKSet.parse(get(first.endpoints.jwksUri).body())
      val secondKeys = com.nimbusds.jose.jwk.JWKSet.parse(get(second.endpoints.jwksUri).body())
      (firstKeys.keys.first().toRSAKey().modulus != secondKeys.keys.first().toRSAKey().modulus) shouldBe true
      val token = ValidationDsl(stove).oidc { mock { accessToken { audience("api") } } }
      first.jwtProcessor("api").process(token.value, null).issuer shouldBe first.endpoints.issuerUrl
      shouldThrow<Exception> { second.jwtProcessor("api").process(token.value, null) }
    } finally {
      stove.close()
    }
  }

  test("factories preserve nested claims, typed dates, defaults and separate ID token audience") {
    val now = Instant.parse("2026-01-02T12:00:00Z")
    withProvider(
      OidcProvider.Mock(clock = Clock.fixed(now, ZoneOffset.UTC), defaults = {
        audience("default")
        claim("tenant", "default")
      })
    ) {
      val token = mock {
        accessToken {
          subject = "alice"
          audience("api", "other")
          scopes("read", "write")
          expiresIn = 5.minutes
          claim("tenant", "override")
          claim("roles", listOf("reader"))
          claim("nested", mapOf("a" to listOf(1, null, true)))
        }
      }
      val claims = SignedJWT.parse(token.value).jwtClaimsSet
      claims.issuer shouldBe endpoints.issuerUrl
      claims.subject shouldBe "alice"
      claims.audience shouldBe listOf("api", "other")
      claims.expirationTime.toInstant() shouldBe now.plusSeconds(300)
      claims.getStringClaim("tenant") shouldBe "override"
      claims.getStringClaim("scope") shouldBe "read write"
      claims.getJSONObjectClaim("nested")["a"] shouldBe listOf(1L, null, true)
      val id = mock { idToken("web", "nonce-value".some()) { subject = "alice" } }
      SignedJWT.parse(id.value).jwtClaimsSet.audience shouldBe listOf("web")
      SignedJWT.parse(id.value).jwtClaimsSet.getStringClaim("nonce") shouldBe "nonce-value"
      shouldThrow<OidcOperationException> { mock { accessToken { claim("iss", "bad") } } }
    }
  }

  test("HTTP resource server accepts valid tokens and rejects invalid issuer, audience, time and signature") {
    withProvider {
      val verifier = jwtProcessor("api")
      val resource = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("localhost", 0), 0)
      resource.createContext("/orders") { exchange ->
        val status = runCatching {
          verifier.process(exchange.requestHeaders.getFirst("Authorization").removePrefix("Bearer "), null)
        }.fold({ 200 }, { 401 })
        exchange.sendResponseHeaders(status, -1)
        exchange.close()
      }
      resource.start()
      try {
        val specifications: List<Pair<Int, MockAccessTokenBuilder.() -> Unit>> = listOf(
          200 to {},
          401 to { issuer = "https://wrong.example" },
          401 to { audience("wrong") },
          401 to { expiresIn = (-5).minutes },
          401 to { notBefore = Instant.now().plusSeconds(300) },
          401 to { invalidSignature = true }
        )
        for ((index, specification) in specifications.withIndex()) {
          val (expected, configure) = specification
          val token = mock {
            accessToken {
              audience("api")
              configure()
            }
          }
          val response = http.send(
            HttpRequest.newBuilder(URI("http://localhost:${resource.address.port}/orders"))
              .header("Authorization", "Bearer ${token.value}").GET().build(),
            HttpResponse.BodyHandlers.discarding()
          )
          io.kotest.assertions.withClue("Token variant $index") { response.statusCode() shouldBe expected }
        }
      } finally {
        resource.stop(0)
      }
    }
  }

  test("shared client handles all authentication methods, authorization code, PKCE and refresh grants") {
    withProvider {
      val auths = listOf(ClientAuthentication.SecretPost("secret"), ClientAuthentication.SecretBasic("special:+ secret"))
      for (auth in auths) {
        val response = token(TokenRequest.ClientCredentials(OidcClient("service", auth), setOf("api")))
        jwtProcessor().process(response.accessToken.value, null).subject shouldBe "service"
      }
      val verifier = "v".repeat(43)
      val client = OidcClient("web")
      mock {
        whenTokenRequested(TokenRequestMatch(clientId = "web".some())) {
          subject = "alice"
          audience("api")
        }
      }
      val code = authorizationCode("web", verifier)
      val tokens = token(TokenRequest.AuthorizationCode(client, code, "http://localhost/callback", verifier.some()))
      tokens.idToken.isSome() shouldBe true
      SignedJWT.parse(tokens.idToken.getOrElse { error("missing ID token") }.value).jwtClaimsSet.audience shouldBe listOf("web")
      SignedJWT.parse(tokens.accessToken.value).jwtClaimsSet.audience shouldBe listOf("api")
      val refresh = tokens.refreshToken.getOrElse { error("no refresh token") }
      val refreshed = token(TokenRequest.RefreshToken(client, refresh))
      jwtProcessor().process(refreshed.accessToken.value, null).issuer shouldBe endpoints.issuerUrl
      val badCode = authorizationCode("web", verifier)
      shouldThrow<OidcTokenEndpointException> {
        token(TokenRequest.AuthorizationCode(client, badCode, "http://localhost/callback", "wrong".some()))
      }
    }
  }

  test("rules prioritize test over suite, preserve per-call immutability and clean up at test end") {
    withProvider {
      mock { whenTokenRequested(TokenRequestMatch(clientId = "service".some())) { subject = "suite" } }
      val ctx = StoveTestContext("a", "A")
      withContext(ctx) {
        reporter.startTest(ctx)
        try {
          mock {
            whenTokenRequested(TokenRequestMatch(clientId = "service".some())) { subject = "test-first" }
            whenTokenRequested(TokenRequestMatch(clientId = "service".some())) { subject = "test-last" }
          }
          val response = token(TokenRequest.ClientCredentials(OidcClient("service", ClientAuthentication.SecretPost("secret"))))
          SignedJWT.parse(response.accessToken.value).jwtClaimsSet.subject shouldBe "test-last"
          mock { shouldHaveBeenCalled(1, "client_credentials") }
        } finally {
          reporter.endTest()
        }
      }
      val response = token(TokenRequest.ClientCredentials(OidcClient("service", ClientAuthentication.SecretPost("secret"))))
      SignedJWT.parse(response.accessToken.value).jwtClaimsSet.subject shouldBe "suite"
    }
  }

  test("concurrent scoped rules cannot consume each other's requests and ambiguous untagged calls fail") {
    withProvider {
      val contexts = listOf(StoveTestContext("a", "A"), StoveTestContext("b", "B"))
      val handles = mutableListOf<OidcTokenRule>()
      for (ctx in contexts) {
        withContext(ctx) {
          reporter.startTest(ctx)
          handles += mock.whenTokenRequested(TokenRequestMatch.clientCredentials("same")) { subject = ctx.testId }
        }
      }
      try {
        coroutineScope {
          contexts.map { ctx ->
            async(ctx) {
              val result = token(TokenRequest.ClientCredentials(OidcClient("same", ClientAuthentication.SecretPost("secret"))))
              SignedJWT.parse(result.accessToken.value).jwtClaimsSet.subject shouldBe ctx.testId
            }
          }.awaitAll()
        }
        val raw = http.send(
          HttpRequest.newBuilder(URI(endpoints.tokenEndpoint))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&client_id=same&client_secret=secret")).build(),
          HttpResponse.BodyHandlers.ofString()
        )
        raw.statusCode() shouldBe 400
        withContext(contexts.first()) {
          mock.calls().count { it.disposition == OidcTokenDisposition.AmbiguousTestOwnership } shouldBe 1
          snapshot().state["routingGuidance"].toString() shouldBe
            "Multiple active tests match an untagged request; use unique request discriminators or separate keyed providers."
          val failure = shouldThrow<IllegalStateException> { handles.first().shouldHaveBeenCalled(2) }
          failure.message.orEmpty() shouldContain "unique request discriminators"
        }
      } finally {
        for (ctx in contexts) withContext(ctx) { reporter.endTest() }
      }
    }
  }

  test("test token results and provider failures remain available in reports") {
    withProvider {
      val marker = "fixture-secret"
      val result = mock.accessToken { claim("private", marker) }
      val id = mock.idToken("web")
      val response = token(TokenRequest.ClientCredentials(OidcClient("service", ClientAuthentication.SecretPost(marker))))
      val request = TokenRequest.RefreshToken(OidcClient("service", ClientAuthentication.SecretBasic(marker)), RefreshToken(marker))
      val error = shouldThrow<OidcTokenEndpointException> { token(request) }

      result.toString() shouldContain result.value
      id.toString() shouldContain id.value
      response.toString() shouldContain response.accessToken.value
      request.toString() shouldContain marker
      ClientAuthentication.SecretPost(marker).toString() shouldContain marker
      TokenRequestMatch.clientCredentials("service", mapOf("client_secret" to marker)).toString() shouldContain marker
      val report = JsonReportRenderer.render(reporter.currentTest(), listOf(snapshot()))
      report shouldContain result.value
      report shouldContain id.value
      report shouldContain response.accessToken.value
      report shouldContain error.oauthError
    }
  }

  test("failed startup releases its port and close is idempotent") {
    val stove = Stove()
    val system = OidcSystem(stove, OidcSystemOptions(OidcProvider.Mock(configure = { error("secret") })))
    try {
      val error = shouldThrow<OidcOperationException> { system.run() }
      error.toString() shouldContain "secret"
      error.cause!!.message shouldBe "secret"
      system.close()
      system.close()
      system.snapshot().state["running"] shouldBe false
    } finally {
      stove.close()
    }
  }
})

private object Partner : SystemKey
