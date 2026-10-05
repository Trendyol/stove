package com.trendyol.stove.oidc

import arrow.core.getOrElse
import arrow.core.some
import com.fasterxml.jackson.core.JsonProcessingException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class OidcProtocolClientTest : FunSpec({
  test("readiness fails immediately on rejected or malformed discovery") {
    val responses = listOf(
      Triple(401, "{}", "HTTP 401"),
      Triple(200, "sensitive-invalid-json", "metadata must be valid JSON"),
      Triple(200, """{"issuer":"https://wrong-issuer.example"}""", "issuer mismatch")
    )
    for ((status, body, guidance) in responses) {
      val attempts = AtomicInteger()
      endpoint({
        attempts.incrementAndGet()
        respond(status, body)
      }) { url, client ->
        val error = shouldThrow<OidcOperationException> {
          withTimeout(2.seconds) { withOidcContext("startup") { client.awaitReady(url) } }
        }
        attempts.get() shouldBe 1
        error.message.orEmpty() shouldContain guidance
        error.message.orEmpty() shouldContain url
        if (body == "sensitive-invalid-json") {
          error.message.orEmpty() shouldContain body
          error.cause.shouldBeInstanceOf<JsonProcessingException>()
        }
      }
    }
  }

  test("readiness propagates explicit cancellation from endpoint configuration without retrying") {
    val cancellation = CancellationException("cancel discovery")
    val attempts = AtomicInteger()
    val options = OidcSystemOptions(resolveEndpoint = {
      attempts.incrementAndGet()
      throw cancellation
    })
    OidcProtocolClient(options).use { client ->
      val error = shouldThrow<CancellationException> {
        withTimeout(2.seconds) { client.awaitReady("http://localhost/issuer") }
      }
      error shouldBe cancellation
      attempts.get() shouldBe 1
    }
  }

  test("readiness retries unavailable metadata and waits for keys") {
    var issuer = ""
    val discoveryAttempts = AtomicInteger()
    val keyAttempts = AtomicInteger()
    endpoint({
      when {
        requestURI.path.endsWith("/jwks") -> {
          val keys = if (keyAttempts.incrementAndGet() == 1) "[]" else """[{"kty":"RSA"}]"""
          respond(200, """{"keys":$keys}""")
        }

        discoveryAttempts.incrementAndGet() == 1 -> respond(503, "{}")

        else -> respond(200, """{"issuer":"$issuer","jwks_uri":"$issuer/jwks","token_endpoint":"$issuer/token","authorization_endpoint":"$issuer/authorize"}""")
      }
    }) { url, client ->
      issuer = url
      val endpoints = withTimeout(5.seconds) { client.awaitReady(issuer) }
      endpoints.issuerUrl shouldBe issuer
      discoveryAttempts.get() shouldBe 3
      keyAttempts.get() shouldBe 2
    }
  }

  test("cancelling readiness stops polling an unavailable provider") {
    val attempted = CompletableDeferred<Unit>()
    endpoint({
      respond(503, "{}")
      attempted.complete(Unit)
    }) { url, client ->
      coroutineScope {
        val readiness = async { client.awaitReady(url) }
        try {
          withTimeout(2.seconds) { attempted.await() }
          withTimeout(2.seconds) { readiness.cancelAndJoin() }
          readiness.isCancelled shouldBe true
        } finally {
          readiness.cancelAndJoin()
        }
      }
    }
  }

  test("discovery rejects credential-bearing endpoints before exposing metadata") {
    var issuer = ""
    endpoint({
      respond(200, """{"issuer":"$issuer","jwks_uri":"$issuer/jwks","token_endpoint":"http://client:secret@localhost/token","authorization_endpoint":"$issuer/authorize"}""")
    }) { url, client ->
      issuer = url
      val error = shouldThrow<OidcOperationException> {
        withOidcContext("startup") { client.discover(issuer, 2.seconds) }
      }
      error.toString() shouldContain "http://client:secret@localhost/token"
      error.cause shouldBe null
    }
  }

  test("opaque tokens and absent optional fields are preserved") {
    endpoint({ respond(200, """{"access_token":"opaque-value","token_type":"Bearer"}""") }) { url, client ->
      val result = client.token(url, TokenRequest.ClientCredentials(OidcClient("service")), null)
      result.accessToken.value shouldBe "opaque-value"
      result.idToken.isNone() shouldBe true
      result.refreshToken.isNone() shouldBe true
      result.expiresIn.isNone() shouldBe true
      result.scopes.isNone() shouldBe true
      result.toString() shouldContain "opaque-value"
    }
  }

  test("provider-specific OAuth errors retain descriptions and failed one-use grants are sent only once") {
    val attempts = AtomicInteger()
    val providerCode = "custom_client_policy"
    val description = "Client fixture-secret is disabled"
    val body = """{"error":"$providerCode","error_description":"$description"}"""
    endpoint({
      attempts.incrementAndGet()
      respond(400, body)
    }) { url, client ->
      val error = shouldThrow<OidcTokenEndpointException> {
        client.token(url, TokenRequest.AuthorizationCode(OidcClient("web"), "one-use-code", "http://localhost/callback"), null)
      }
      error.httpStatus shouldBe 400
      error.oauthError shouldBe providerCode
      error.errorDescription shouldBe description.some()
      error.responseBody shouldBe body
      error.toString() shouldContain description
      attempts.get() shouldBe 1
    }
  }

  test("non-JSON endpoint failures retain HTTP status, response body and parser cause") {
    val body = "<html>Test gateway rejected fixture-secret</html>"
    endpoint({ respond(502, body) }) { url, client ->
      val error = shouldThrow<OidcTokenEndpointException> {
        client.token(url, TokenRequest.ClientCredentials(OidcClient("service")), null)
      }
      error.httpStatus shouldBe 502
      error.oauthError shouldBe "unknown_error"
      error.responseBody shouldBe body
      error.message.orEmpty() shouldContain body
      error.cause.shouldBeInstanceOf<JsonProcessingException>()
    }
  }

  test("redirects never forward token request credentials") {
    val attempts = AtomicInteger()
    endpoint({
      attempts.incrementAndGet()
      responseHeaders.add("Location", "/stolen")
      respond(307, "")
    }) { url, client ->
      val error = shouldThrow<OidcTokenEndpointException> {
        client.token(url, TokenRequest.ClientCredentials(OidcClient("client", ClientAuthentication.SecretPost("secret"))), null)
      }
      error.httpStatus shouldBe 307
      attempts.get() shouldBe 1
    }
  }

  test("basic auth applies OAuth form encoding and code exchange preserves PKCE") {
    var authentication = ""
    var form = ""
    var testHeader = ""
    endpoint({
      authentication = requestHeaders.getFirst("Authorization")
      testHeader = requestHeaders.getFirst("X-Stove-Test-Id")
      form = requestBody.bufferedReader().readText()
      respond(200, """{"access_token":"opaque","token_type":"Bearer","expires_in":60,"scope":"read write","refresh_token":"refresh","id_token":"id"}""")
    }) { url, client ->
      val result = client.token(
        url,
        TokenRequest.AuthorizationCode(
          OidcClient("client:with space", ClientAuthentication.SecretBasic("s+ecret:")),
          "one+code",
          "http://localhost/callback",
          "verifier".some()
        ),
        "test-a"
      )
      String(Base64.getDecoder().decode(authentication.removePrefix("Basic "))) shouldBe "client%3Awith+space:s%2Becret%3A"
      val parsed = form.split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
      parsed["code"] shouldBe "one+code"
      parsed["code_verifier"] shouldBe "verifier"
      parsed.containsKey("client_secret") shouldBe false
      parsed.containsKey("client_id") shouldBe false
      testHeader shouldBe "test-a"
      result.scopes.getOrElse { emptySet() } shouldBe setOf("read", "write")
    }
  }

  test("oversized response identifies the transport limit") {
    endpoint({ respond(200, "sensitive-".repeat(120_000)) }) { url, client ->
      val error = shouldThrow<OidcOperationException> {
        client.token(url, TokenRequest.ClientCredentials(OidcClient("service")), null)
      }
      error.message.orEmpty() shouldContain "response too large"
      error.cause.shouldBeInstanceOf<IllegalStateException>()
    }
  }

  test("cancellation interrupts a response whose body has not completed") {
    val receiving = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    endpoint({
      sendResponseHeaders(200, 0)
      responseBody.write('{'.code)
      responseBody.flush()
      receiving.complete(Unit)
      release.await(5, TimeUnit.SECONDS)
    }) { url, client ->
      coroutineScope {
        val request = async { client.token(url, TokenRequest.ClientCredentials(OidcClient("service")), null) }
        try {
          withTimeout(2.seconds) { receiving.await() }
          withTimeout(2.seconds) { request.cancelAndJoin() }
          request.isCancelled shouldBe true
        } finally {
          release.countDown()
          request.cancelAndJoin()
        }
      }
    }
  }

  test("closing the protocol client prevents further requests") {
    val attempts = AtomicInteger()
    endpoint({
      attempts.incrementAndGet()
      respond(200, """{"access_token":"opaque","token_type":"Bearer"}""")
    }) { url, client ->
      client.token(url, TokenRequest.ClientCredentials(OidcClient("service")), null)
      client.close()
      shouldThrow<Exception> {
        withTimeout(2.seconds) { client.token(url, TokenRequest.ClientCredentials(OidcClient("service")), null) }
      }
      attempts.get() shouldBe 1
    }
  }

  test("malformed successful response retains the body and parser cause") {
    endpoint({ respond(200, "sensitive-non-json") }) { url, client ->
      val error = shouldThrow<OidcOperationException> {
        client.token(url, TokenRequest.ClientCredentials(OidcClient("service")), null)
      }
      error.toString() shouldContain "sensitive-non-json"
      error.cause.shouldBeInstanceOf<JsonProcessingException>()
    }
  }
})

private suspend fun endpoint(handler: HttpExchange.() -> Unit, block: suspend (String, OidcProtocolClient) -> Unit) {
  val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
  server.createContext("/") { exchange -> exchange.use { handler(it) } }
  server.start()
  try {
    OidcProtocolClient(OidcSystemOptions()).use { client ->
      block("http://localhost:${server.address.port}/token", client)
    }
  } finally {
    server.stop(0)
  }
}

private fun HttpExchange.respond(status: Int, body: String) {
  val bytes = body.toByteArray()
  responseHeaders.add("Content-Type", "application/json")
  sendResponseHeaders(status, bytes.size.toLong())
  responseBody.write(bytes)
}
