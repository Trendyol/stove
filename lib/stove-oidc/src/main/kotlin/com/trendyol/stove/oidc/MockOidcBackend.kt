package com.trendyol.stove.oidc

import arrow.core.None
import arrow.core.Option
import arrow.core.getOrElse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.OAuth2Config
import no.nav.security.mock.oauth2.http.OAuth2HttpRequest
import no.nav.security.mock.oauth2.http.OAuth2HttpServer
import no.nav.security.mock.oauth2.http.RequestHandler
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import no.nav.security.mock.oauth2.token.KeyProvider
import no.nav.security.mock.oauth2.token.OAuth2TokenProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class MockOidcBackend(private val provider: OidcProvider.Mock) : OidcBackend {
  private val config = OAuth2Config(tokenProvider = freshSigner()).run(provider.configure)
  val rules = MockRules(provider, defaultCallback(), ::issuerUrl)
  override val testLifecycle = MockTestLifecycle(rules)
  private val server = MockOAuth2Server(serverConfiguration())
  private var phase = OidcBackendPhase.Created
  private val signerLock = ReentrantLock()
  private val invalidSigner = lazy(LazyThreadSafetyMode.NONE, ::freshSigner)

  override suspend fun start(): String = runInterruptible(Dispatchers.IO) {
    check(phase == OidcBackendPhase.Created) { "OIDC provider has already been started or closed" }
    server.start(InetAddress.getByName(provider.bindAddress), provider.port)
    phase = OidcBackendPhase.Running
    issuerUrl()
  }

  private fun serverConfiguration(): OAuth2Config {
    val http = MockOidcHttpServer(config.httpServer, rules, provider.issuerUrl)
    return config.copy(httpServer = http, tokenCallbacks = setOf(rules.tokenCallback))
  }

  private fun defaultCallback() = config.tokenCallbacks.firstOrNull { it.issuerId() == provider.issuerId }
    ?: DefaultOAuth2TokenCallback(issuerId = provider.issuerId)

  private fun issuerUrl(): String = provider.issuerUrl.getOrElse { server.issuerUrl(provider.issuerId).toString() }

  override fun mockControls(system: OidcSystem): MockOidcControls = MockOidcControls(system, this)

  override fun evidence(testId: String): Map<String, Any> {
    val calls = rules.calls.entriesWithinTest(testId)
    return mapOf(
      "tokenCalls" to calls.map { call ->
        mapOf("grantType" to call.grantType, "httpStatus" to call.httpStatus, "disposition" to call.disposition.toString())
      },
      "routingGuidance" to ambiguityGuidance(calls)
    )
  }

  fun builder(configure: MutableMockToken.() -> Unit): MutableMockToken {
    check(phase == OidcBackendPhase.Running) { "OIDC provider is not running" }
    return MutableMockToken().apply(provider.defaults).apply(configure).also { it.validate() }.frozenCopy()
  }

  fun accessToken(configure: MockAccessTokenBuilder.() -> Unit): String = issue(builder(configure))

  fun idToken(clientId: String, nonce: Option<String>, configure: MockIdTokenBuilder.() -> Unit): String =
    issue(builder(configure).apply { audience(clientId) }, nonce)

  private fun issue(builder: MutableMockToken, nonce: Option<String> = None): String {
    check(phase == OidcBackendPhase.Running) { "OIDC provider is not running" }
    val spec = builder.build(issuerUrl(), provider.clock.instant())
    val claims = spec.claims.toMutableMap()
    nonce.onSome { claims["nonce"] = it }
    val signer = if (spec.invalidSignature) unpublishedSigner() else server.config.tokenProvider
    return signer.jwt(claims, issuerId = provider.issuerId).serialize()
  }

  private fun unpublishedSigner(): OAuth2TokenProvider = signerLock.withLock { invalidSigner.value }

  // An empty key list asks NAV to generate keys instead of using its bundled test keys.
  private fun freshSigner(): OAuth2TokenProvider = OAuth2TokenProvider(KeyProvider(emptyList()))

  override fun close() {
    if (phase == OidcBackendPhase.Closed) return
    phase = OidcBackendPhase.Closed
    try {
      server.shutdown()
    } finally {
      rules.clear()
    }
  }
}

/** Decorates NAV's transport with canonical routing and Stove request rules. */
private class MockOidcHttpServer(
  private val delegate: OAuth2HttpServer,
  private val rules: MockRules,
  canonicalIssuer: Option<String>
) : OAuth2HttpServer by delegate {
  private val canonical = canonicalIssuer.map { it.toHttpUrl() }
  override fun start(inetAddress: InetAddress, port: Int, requestHandler: RequestHandler): OAuth2HttpServer =
    delegate.start(inetAddress, port) { request -> rules.handle(route(request), requestHandler) }

  private fun route(request: OAuth2HttpRequest): OAuth2HttpRequest = canonical.fold({ request }) { issuer ->
    val headers = request.headers.newBuilder()
      .set("Host", URI(issuer.toString()).rawAuthority)
      .set("X-Forwarded-Proto", issuer.scheme)
      .set("X-Forwarded-Port", issuer.port.toString())
      .build()
    request.copy(headers = headers)
  }
}
