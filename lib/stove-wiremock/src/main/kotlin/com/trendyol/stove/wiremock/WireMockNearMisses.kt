package com.trendyol.stove.wiremock

import com.github.tomakehurst.wiremock.matching.RequestPattern
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import com.github.tomakehurst.wiremock.verification.diff.Diff

/**
 * Renders field-level diffs between unmatched evidence and its closest candidates,
 * so failures explain *why* nothing matched instead of just that nothing did.
 * Candidates come from the test-scoped journal, never the whole suite.
 */
internal object WireMockNearMisses {
  private const val MAX_CANDIDATES = 3

  /** Closest registered stubs for an unmatched request, ranked by match distance. */
  fun closestStubsFor(request: LoggedRequest, stubs: List<StubMapping>): String {
    if (stubs.isEmpty()) return "No stubs were registered for this test."
    return stubs
      .map { stub -> stub to stub.request.match(request).distance }
      .sortedBy { (_, distance) -> distance }
      .take(MAX_CANDIDATES)
      .joinToString("\n") { (stub, distance) ->
        if (distance == 0.0) {
          "Stub ${stub.displayName()} matches this request exactly but was not active when the " +
            "request arrived — it was already consumed (removeStubAfterRequestMatched) or registered later."
        } else {
          Diff(stub.request, request).toString()
        }
      }
  }

  /** Closest received requests for a verification pattern that matched nothing. */
  fun closestRequestsFor(pattern: RequestPattern, requests: List<LoggedRequest>): String {
    if (requests.isEmpty()) return "No requests were received in this test."
    return requests
      .map { request -> request to pattern.match(request).distance }
      .sortedBy { (_, distance) -> distance }
      .take(MAX_CANDIDATES)
      .joinToString("\n") { (request, _) -> Diff(pattern, request).toString() }
  }

  private fun StubMapping.displayName(): String =
    name?.takeIf { it.isNotBlank() } ?: "${request.method?.value() ?: "?"} ${request.url ?: request.urlPath ?: ""}".trim()
}
