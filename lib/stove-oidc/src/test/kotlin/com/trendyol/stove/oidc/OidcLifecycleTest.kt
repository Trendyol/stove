package com.trendyol.stove.oidc

import arrow.core.some
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.SignedJWT
import com.trendyol.stove.system.Stove
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import no.nav.security.mock.oauth2.http.MockWebServerWrapper
import no.nav.security.mock.oauth2.http.OAuth2HttpServer
import no.nav.security.mock.oauth2.http.RequestHandler
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.time.Duration.Companion.milliseconds

class OidcLifecycleTest : FunSpec({
  test("only a running system exposes endpoints and restart owns fresh resources") {
    Stove().use { stove ->
      OidcSystem(stove, OidcSystemOptions()).use { system ->
        shouldThrow<IllegalStateException> { system.endpoints }
        system.run()
        val originalKeys = JWKSet.parse(get(system.endpoints.jwksUri).body())
        val originalControls = system.mock { this }
        shouldThrow<IllegalStateException> { system.run() }
        system.snapshot().state["running"] shouldBe true

        system.close()
        system.close()
        system.snapshot().state["running"] shouldBe false
        system.snapshot().state["endpoints"] shouldBe emptyMap<String, String>()
        shouldThrow<IllegalStateException> { system.endpoints }
        var configured = false
        shouldThrow<OidcOperationException> { originalControls.accessToken { configured = true } }
        configured shouldBe false

        system.run()
        val restartedKeys = JWKSet.parse(get(system.endpoints.jwksUri).body())
        (originalKeys.keys.first().toRSAKey().modulus != restartedKeys.keys.first().toRSAKey().modulus) shouldBe true
        shouldThrow<OidcOperationException> { originalControls.accessToken() }
        val token = system.mock { accessToken { subject = "after-restart" } }
        system.jwtProcessor().process(token.value, null).subject shouldBe "after-restart"
      }
    }
  }

  test("client construction failure closes the already started provider") {
    val transport = MockWebServerWrapper()
    val options = OidcSystemOptions(
      provider = OidcProvider.Mock(configure = { copy(httpServer = transport) }),
      configureHttpClient = { error("sensitive client configuration") }
    )
    Stove().use { stove ->
      OidcSystem(stove, options).use { system ->
        val error = shouldThrow<OidcOperationException> { system.run() }
        error.stackTraceToString() shouldContain "sensitive client configuration"
        system.snapshot().state["running"] shouldBe false
        shouldThrow<java.io.IOException> {
          Socket().use { it.connect(InetSocketAddress("localhost", transport.port()), 500) }
        }
      }
    }
  }

  test("failed startup preserves cleanup failure and its cause without replacing the startup error") {
    val transport = MockWebServerWrapper()
    var shutdowns = 0
    val provider = OidcProvider.Mock(configure = {
      copy(
        httpServer = object : OAuth2HttpServer by transport {
          override fun start(inetAddress: InetAddress, port: Int, requestHandler: RequestHandler): OAuth2HttpServer {
            transport.start(inetAddress, port, requestHandler)
            error("sensitive startup detail")
          }

          override fun stop(): OAuth2HttpServer {
            shutdowns++
            transport.stop()
            error("sensitive shutdown detail")
          }
        }
      )
    })
    Stove().use { stove ->
      OidcSystem(stove, OidcSystemOptions(provider)).use { system ->
        val error = shouldThrow<OidcOperationException> { system.run() }
        error.message shouldBe "OIDC startup failed; sensitive startup detail"
        error.cause!!.message shouldBe "sensitive startup detail"
        val cleanup = error.suppressed.single()
        cleanup.message shouldBe "OIDC shutdown failed; sensitive shutdown detail"
        cleanup.cause!!.message shouldBe "sensitive shutdown detail"
        error.stackTraceToString() shouldContain "sensitive startup detail"
        error.stackTraceToString() shouldContain "sensitive shutdown detail"
        shutdowns shouldBe 1
        system.snapshot().state["running"] shouldBe false
      }
    }
    shutdowns shouldBe 1
  }

  test("a provider that fails after binding is closed while retaining its native exception") {
    val transport = MockWebServerWrapper()
    var boundPort = 0
    val stove = Stove()
    val system = OidcSystem(
      stove,
      OidcSystemOptions(
        OidcProvider.Mock(configure = {
          copy(
            httpServer = object : OAuth2HttpServer by transport {
              override fun start(inetAddress: InetAddress, port: Int, requestHandler: RequestHandler): OAuth2HttpServer {
                transport.start(inetAddress, port, requestHandler)
                boundPort = transport.port()
                error("native-sensitive-value")
              }
            }
          )
        })
      )
    )
    try {
      val error = shouldThrow<OidcOperationException> { system.run() }
      error.cause!!.message shouldBe "native-sensitive-value"
      shouldThrow<java.io.IOException> { Socket().use { it.connect(InetSocketAddress("localhost", boundPort), 500) } }
      system.snapshot().state["running"] shouldBe false
      system.close()
    } finally {
      system.close()
      stove.close()
    }
  }

  test("discovery timeout cleans up the runtime") {
    val transport = MockWebServerWrapper()
    val stove = Stove()
    val system = OidcSystem(
      stove,
      OidcSystemOptions(
        provider = OidcProvider.Mock(configure = { copy(httpServer = transport) }),
        readinessTimeout = 250.milliseconds,
        requestTimeout = 100.milliseconds,
        resolveEndpoint = { "http://localhost:9/unreachable" }
      )
    )
    try {
      shouldThrow<kotlinx.coroutines.TimeoutCancellationException> { system.run() }
      system.snapshot().state["running"] shouldBe false
      system.close()
    } finally {
      system.close()
      stove.close()
    }
  }

  test("NAV canonical issuer agrees across host discovery, protocol grants and direct tokens") {
    val transport = MockWebServerWrapper()
    val stove = Stove()
    val canonical = "http://oidc.example.test/stove"
    val system = OidcSystem(
      stove,
      OidcSystemOptions(
        provider = OidcProvider.Mock(issuerUrl = canonical.some(), configure = { copy(httpServer = transport) }),
        resolveEndpoint = { it.replace("http://oidc.example.test", "http://localhost:${transport.port()}") }
      )
    )
    try {
      system.run()
      system.endpoints.issuerUrl shouldBe canonical
      val direct = system.mock { accessToken() }
      val protocol = system.token(TokenRequest.ClientCredentials(OidcClient("service", ClientAuthentication.SecretPost("secret"))))
      SignedJWT.parse(direct.value).jwtClaimsSet.issuer shouldBe canonical
      SignedJWT.parse(protocol.accessToken.value).jwtClaimsSet.issuer shouldBe canonical
    } finally {
      system.close()
      stove.close()
    }
  }

  test("registered rules freeze mutable input and issue fresh token identifiers") {
    withProvider {
      lateinit var captured: MockTokenBuilder
      val roles = mutableListOf("reader")
      val handle = mock {
        whenTokenRequested(TokenRequestMatch(clientId = "service".some())) {
          captured = this
          subject = "original"
          claim("roles", roles)
        }
      }
      try {
        captured.subject = "modified"
        roles.add("admin")
        val request = TokenRequest.ClientCredentials(OidcClient("service", ClientAuthentication.SecretPost("secret")))
        val first = SignedJWT.parse(token(request).accessToken.value).jwtClaimsSet
        val second = SignedJWT.parse(token(request).accessToken.value).jwtClaimsSet
        first.subject shouldBe "original"
        first.getStringListClaim("roles") shouldBe listOf("reader")
        (first.jwtid != second.jwtid) shouldBe true
      } finally {
        handle.close()
      }
    }
  }
})
