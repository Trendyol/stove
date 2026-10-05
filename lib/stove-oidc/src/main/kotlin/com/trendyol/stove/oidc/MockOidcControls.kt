package com.trendyol.stove.oidc

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import com.nimbusds.oauth2.sdk.TokenRequest as NativeTokenRequest

/** Matching fields are compared exactly. Use a unique client id to distinguish concurrent AUT traffic. */
class TokenRequestMatch(
  val clientId: Option<String> = None,
  val grantType: Option<String> = None,
  val parameters: Map<String, String> = emptyMap()
) {
  internal fun matches(request: NativeTokenRequest): Boolean {
    val id = request.clientAuthentication?.clientID?.value ?: request.clientID?.value
    val fields = request.toHTTPRequest().bodyAsFormParameters
    return clientId.fold({ true }, { it == id }) &&
      grantType.fold({ true }, { it == request.authorizationGrant.type.value }) &&
      parameters.all { (key, value) -> fields[key] == listOf(value) }
  }
  override fun toString(): String = "TokenRequestMatch(clientId=$clientId, grantType=$grantType, parameters=$parameters)"

  companion object {
    fun clientCredentials(clientId: String, parameters: Map<String, String> = emptyMap()): TokenRequestMatch =
      TokenRequestMatch(Some(clientId), Some("client_credentials"), parameters)

    fun authorizationCode(clientId: String, parameters: Map<String, String> = emptyMap()): TokenRequestMatch =
      TokenRequestMatch(Some(clientId), Some("authorization_code"), parameters)

    fun refreshToken(clientId: String, parameters: Map<String, String> = emptyMap()): TokenRequestMatch =
      TokenRequestMatch(Some(clientId), Some("refresh_token"), parameters)
  }
}

sealed interface OidcTokenDisposition {
  @ConsistentCopyVisibility
  data class Matched internal constructor(val ruleId: Long) : OidcTokenDisposition
  data object Fallback : OidcTokenDisposition
  data object AmbiguousTestOwnership : OidcTokenDisposition
}

/** The grant, status and routing outcome of a token request. */
data class OidcTokenCall(val grantType: String, val httpStatus: Int, val disposition: OidcTokenDisposition) {
  val matchedRule: Boolean get() = disposition is OidcTokenDisposition.Matched
}

@OidcDsl
class MockOidcControls internal constructor(private val system: OidcSystem, private val backend: MockOidcBackend) {
  suspend fun accessToken(configure: MockAccessTokenBuilder.() -> Unit = {}): AccessToken =
    system.operation("issue mock access token") { AccessToken(backend.accessToken(configure)) }

  /** Audience is the relying-party client id, and nonce is included only when supplied. */
  suspend fun idToken(
    clientId: String,
    nonce: Option<String> = None,
    configure: MockIdTokenBuilder.() -> Unit = {}
  ): IdToken = system.operation("issue mock ID token") {
    IdToken(backend.idToken(clientId, nonce, configure))
  }

  /** The rule belongs to the current Stove test, or to the suite when registered outside a test. */
  fun whenTokenRequested(match: TokenRequestMatch, configure: MockTokenBuilder.() -> Unit): OidcTokenRule {
    val builder = withOidcContext("mock rule configuration") { backend.builder(configure) }
    val id = backend.rules.add(system.reporter.currentTestIdOrNull(), match, builder)
    return OidcTokenRule(system, backend.rules, id)
  }

  fun calls(): List<OidcTokenCall> = backend.rules.calls.entriesWithinTest(system.reporter.currentTestId())

  /** Point-in-time aggregate verification; use a rule handle to verify a particular registration. */
  suspend fun shouldHaveBeenCalled(times: Int, grantType: String? = null) {
    val evidence = calls()
    verifyTokenCalls(system, times, evidence.count { grantType == null || it.grantType == grantType }, "all matching grants", evidence)
  }
}
