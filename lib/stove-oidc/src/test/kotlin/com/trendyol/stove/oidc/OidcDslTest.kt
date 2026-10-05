package com.trendyol.stove.oidc

import com.sun.net.httpserver.HttpServer
import com.trendyol.stove.http.HttpClientSystemOptions
import com.trendyol.stove.http.httpClient
import com.trendyol.stove.reporting.StoveTestContext
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.ValidationDsl
import com.trendyol.stove.system.abstractions.SystemKey
import com.trendyol.stove.system.abstractions.SystemNotRegisteredException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import com.trendyol.stove.http.http as validateHttp

class OidcDslTest : FunSpec({
  test("rule assertions reject other rules and fallback traffic and retain evidence after removal") {
    withProvider {
      val orders = mock.whenTokenRequested(TokenRequestMatch.clientCredentials("orders")) { subject = "alice" }
      mock.whenTokenRequested(TokenRequestMatch.clientCredentials("other")) { subject = "bob" }

      token(clientCredentials("other"))
      token(clientCredentials("fallback"))
      mock.shouldHaveBeenCalled(2, "client_credentials")
      orders.shouldHaveBeenCalled(0)
      val failure = shouldThrow<IllegalStateException> { orders.shouldHaveBeenCalled() }
      failure.message.orEmpty() shouldContain "observed 0"
      failure.message.orEmpty() shouldContain "Fallback"

      token(clientCredentials("orders"))
      orders.shouldHaveBeenCalled()
      orders.calls().single().httpStatus shouldBe 200
      orders.close()
      orders.close()
      token(clientCredentials("orders"))
      orders.shouldHaveBeenCalled()
      mock.calls().last().disposition shouldBe OidcTokenDisposition.Fallback
    }
  }

  test("suite rule handles verify only the current test's calls") {
    withProvider {
      val orders = mock.whenTokenRequested(TokenRequestMatch.clientCredentials("orders")) { subject = "alice" }
      for (id in listOf("first", "second")) {
        val context = StoveTestContext(id, id)
        withContext(context) {
          reporter.startTest(context)
          try {
            orders.shouldHaveBeenCalled(0)
            token(clientCredentials("orders"))
            orders.shouldHaveBeenCalled()
          } finally {
            reporter.endTest()
          }
        }
      }
    }
  }

  test("invalid rule lifetimes fail during registration without installing a rule") {
    withProvider {
      val failure = shouldThrow<OidcConfigurationException> {
        mock.whenTokenRequested(TokenRequestMatch.clientCredentials("orders")) { expiresIn = Duration.INFINITE }
      }
      failure.field shouldBe "expiresIn"
      failure.message.orEmpty() shouldContain "finite"
      token(clientCredentials("orders"))
      mock.calls().single().disposition shouldBe OidcTokenDisposition.Fallback

      val expired = mock.whenTokenRequested(TokenRequestMatch.clientCredentials("expired")) { expiresIn = (-5).minutes }
      // NAV reports a negative expires_in for an expired rule; the protocol client rejects that metadata.
      val responseFailure = shouldThrow<OidcOperationException> { token(clientCredentials("expired")) }
      responseFailure.message.orEmpty() shouldContain "expires_in"
      expired.shouldHaveBeenCalled()
    }
  }

  test("configuration errors retain guidance and callback failures retain their original cause") {
    withProvider {
      val failure = shouldThrow<OidcConfigurationException> {
        mock.accessToken { claim("iss", "sensitive-value") }
      }
      failure.message.orEmpty() shouldContain "Use issuer"
      failure.cause shouldBe null

      val cause = IllegalStateException("Invalid test secret: fixture-secret")
      val callbackFailure = shouldThrow<OidcOperationException> {
        mock.accessToken { throw cause }
      }
      callbackFailure.message.orEmpty() shouldContain cause.message!!
      callbackFailure.cause shouldBe cause
      reporter.currentTest().entries().toString() shouldContain cause.message!!
    }
  }

  test("missing keyed registration identifies the requested key") {
    Stove().use { stove ->
      stove.with { oidc() }
      val failure = shouldThrow<SystemNotRegisteredException> { ValidationDsl(stove).oidc(MissingIssuer) { endpoints } }
      failure.message.orEmpty() shouldContain "MissingIssuer"
      failure.message.orEmpty() shouldContain "oidc(key)"
    }
  }

  test("access tokens authenticate Stove HTTP requests and remain visible in reports") {
    withProvider {
      val access = mock.accessToken { audience("orders-api") }
      val resource = HttpServer.create(InetSocketAddress("localhost", 0), 0)
      resource.createContext("/orders") { exchange ->
        exchange.use {
          val authorized = it.requestHeaders.getFirst("Authorization") == "Bearer ${access.value}"
          it.sendResponseHeaders(if (authorized) 200 else 401, -1)
        }
      }
      resource.start()
      try {
        stove.with { httpClient { HttpClientSystemOptions("http://localhost:${resource.address.port}") } }
        ValidationDsl(stove).validateHttp {
          getBodilessResponse("/orders", token = access.asHttpToken()) { it.status shouldBe 200 }
        }
        reporter.currentTest().entries().toString() shouldContain access.value
      } finally {
        resource.stop(0)
      }
    }
  }
})

private fun clientCredentials(clientId: String): TokenRequest =
  TokenRequest.ClientCredentials(OidcClient(clientId, ClientAuthentication.SecretPost("test-secret")))

private object MissingIssuer : SystemKey
