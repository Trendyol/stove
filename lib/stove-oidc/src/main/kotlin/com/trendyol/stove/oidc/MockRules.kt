package com.trendyol.stove.oidc

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.oauth2.sdk.ParseException
import com.trendyol.stove.scoping.TestScopedJournal
import com.trendyol.stove.scoping.stoveTestId
import kotlinx.coroutines.CancellationException
import no.nav.security.mock.oauth2.http.OAuth2HttpRequest
import no.nav.security.mock.oauth2.http.OAuth2HttpResponse
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import no.nav.security.mock.oauth2.token.OAuth2TokenCallback
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import com.nimbusds.oauth2.sdk.TokenRequest as NativeTokenRequest

internal class MockRules(
  private val provider: OidcProvider.Mock,
  default: OAuth2TokenCallback,
  private val issuer: () -> String
) {
  private class Rule(val id: Long, val owner: String?, val match: TokenRequestMatch, val builder: MutableMockToken)
  private sealed interface Selection {
    data object Ambiguous : Selection
    sealed interface Resolved : Selection
    data object Fallback : Resolved
    class Matched(val rule: Rule) : Resolved
  }

  private val sequence = AtomicLong()
  private val rules = CopyOnWriteArrayList<Rule>()
  val calls = TestScopedJournal<OidcTokenCall>()
  private val callback = RequestScopedTokenCallback(provider.issuerId, default)
  private val json = ObjectMapper()
  val tokenCallback: OAuth2TokenCallback get() = callback

  fun add(owner: String?, match: TokenRequestMatch, builder: MutableMockToken): Long {
    require(!builder.invalidSignature) { "Invalid signatures are supported by direct token factories only" }
    val frozenMatch = TokenRequestMatch(match.clientId, match.grantType, match.parameters.toMap())
    val rule = Rule(sequence.incrementAndGet(), owner, frozenMatch, builder)
    rules.add(rule)
    return rule.id
  }

  fun remove(id: Long) {
    rules.removeIf { it.id == id }
  }

  fun remove(owner: String) {
    rules.removeIf { it.owner == owner }
  }

  fun clear() {
    rules.clear()
    calls.clearAll()
  }

  fun handle(request: OAuth2HttpRequest, next: (OAuth2HttpRequest) -> OAuth2HttpResponse): OAuth2HttpResponse {
    if (!isTokenRequest(request)) return next(request)
    val owner = request.headers.toMap().stoveTestId()
    val parsed = parseTokenRequest(request)
    return when (val selection = selectRule(parsed, owner)) {
      Selection.Ambiguous -> rejectAmbiguous(parsed)
      is Selection.Resolved -> dispatch(request, parsed, owner, selection, next)
    }
  }

  private fun parseTokenRequest(request: OAuth2HttpRequest): NativeTokenRequest? = try {
    request.asNimbusTokenRequest()
  } catch (_: ParseException) {
    // Let NAV return its protocol error for malformed requests.
    null
  }

  private fun isTokenRequest(request: OAuth2HttpRequest): Boolean =
    request.method == "POST" && request.url.encodedPath == "/${provider.issuerId}/token"

  private fun selectRule(request: NativeTokenRequest?, owner: String?): Selection {
    if (request == null) return Selection.Fallback
    val matching = rules.filter { it.match.matches(request) }
    val owned = matching.filter { it.owner != null && (owner == null || it.owner == owner) }
    // Unattributed requests may route only when all matching test rules have the same owner.
    if (owner == null && owned.map { it.owner }.distinct().size > 1) return Selection.Ambiguous
    val candidates = owned.ifEmpty { matching.filter { it.owner == null } }
    val match = candidates.maxByOrNull { it.id } ?: return Selection.Fallback
    return Selection.Matched(match)
  }

  private fun rejectAmbiguous(request: NativeTokenRequest?): OAuth2HttpResponse {
    calls.record(null, OidcTokenCall(grantType(request), 400, OidcTokenDisposition.AmbiguousTestOwnership))
    return errorResponse(400, "invalid_request", "Multiple tests match this request; use X-Stove-Test-Id or unique request discriminators")
  }

  private fun dispatch(
    request: OAuth2HttpRequest,
    parsed: NativeTokenRequest?,
    owner: String?,
    selection: Selection.Resolved,
    next: (OAuth2HttpRequest) -> OAuth2HttpResponse
  ): OAuth2HttpResponse {
    val response = try {
      val selected = when (selection) {
        Selection.Fallback -> callback.fallback
        is Selection.Matched -> callbackFor(selection.rule)
      }
      callback.withSelected(selected) { next(request) }
    } catch (error: CancellationException) {
      throw error
    } catch (error: InterruptedException) {
      Thread.currentThread().interrupt()
      throw error
    } catch (error: Exception) {
      errorResponse(500, "server_error", error.message ?: error.javaClass.simpleName)
    }
    val evidenceOwner = if (selection is Selection.Matched) owner ?: selection.rule.owner else owner
    val disposition = when (selection) {
      Selection.Fallback -> OidcTokenDisposition.Fallback
      is Selection.Matched -> OidcTokenDisposition.Matched(selection.rule.id)
    }
    calls.record(evidenceOwner, OidcTokenCall(grantType(parsed), response.status, disposition))
    return response
  }

  private fun callbackFor(rule: Rule): OAuth2TokenCallback {
    val spec = rule.builder.build(issuer(), provider.clock.instant())
    check(!spec.invalidSignature) { "Invalid signatures are supported by direct token factories only" }
    return MockRuleTokenCallback(provider.issuerId, spec)
  }

  private fun grantType(request: NativeTokenRequest?): String = request?.authorizationGrant?.type?.value ?: "unknown"

  private fun errorResponse(status: Int, code: String, description: String): OAuth2HttpResponse =
    OAuth2HttpResponse(status = status, body = json.writeValueAsString(mapOf("error" to code, "error_description" to description)))
}

/** NAV retains this callback for refresh grants; the selected rule exists only during one request. */
private class RequestScopedTokenCallback(
  private val issuerId: String,
  val fallback: OAuth2TokenCallback
) : OAuth2TokenCallback {
  private val selected = ThreadLocal.withInitial { fallback }

  fun <T> withSelected(callback: OAuth2TokenCallback, action: () -> T): T {
    selected.set(callback)
    try {
      return action()
    } finally {
      selected.remove()
    }
  }

  override fun issuerId(): String = issuerId
  override fun subject(tokenRequest: NativeTokenRequest): String? = current().subject(tokenRequest)
  override fun audience(tokenRequest: NativeTokenRequest): List<String> = current().audience(tokenRequest)
  override fun addClaims(tokenRequest: NativeTokenRequest): Map<String, Any> = current().addClaims(tokenRequest)
  override fun typeHeader(tokenRequest: NativeTokenRequest): String = current().typeHeader(tokenRequest)
  override fun tokenExpiry(): Long = current().tokenExpiry()
  private fun current(): OAuth2TokenCallback = selected.get()
}

private class MockRuleTokenCallback(issuerId: String, private val spec: MockTokenSpec) : DefaultOAuth2TokenCallback(issuerId = issuerId) {
  override fun subject(tokenRequest: NativeTokenRequest): String = spec.claims["sub"] as String
  override fun audience(tokenRequest: NativeTokenRequest): List<String> = (spec.claims["aud"] as List<*>).filterIsInstance<String>()

  // NAV supplies the relying-party audience separately for ID tokens.
  override fun addClaims(tokenRequest: NativeTokenRequest): Map<String, Any> = spec.claims - "aud"
}
