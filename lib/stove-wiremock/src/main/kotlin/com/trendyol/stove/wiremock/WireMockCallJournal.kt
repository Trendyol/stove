package com.trendyol.stove.wiremock

import com.github.tomakehurst.wiremock.stubbing.ServeEvent
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import com.trendyol.stove.scoping.TestScopedJournal
import com.trendyol.stove.scoping.stoveTestId

/**
 * Test-scoped journal of stubs and serve events, backed by the fail-open
 * [TestScopedJournal]: entries provably tagged with another test are excluded,
 * untagged entries are visible to every test. Requests are attributed by their
 * `X-Stove-Test-Id` header or baggage first, then by the matched stub's tag.
 */
internal class WireMockCallJournal {
  private val stubs = TestScopedJournal<StubMapping>()
  private val serveEvents = TestScopedJournal<ServeEvent>()

  fun recordStub(stubMapping: StubMapping) {
    stubs.record(stubMapping.stoveTestId(), stubMapping)
  }

  fun record(serveEvent: ServeEvent) {
    serveEvents.record(serveEvent.stoveTestId(), serveEvent)
  }

  fun requests(testId: String): List<LoggedRequest> =
    serveEvents(testId).map { it.request }

  fun stubs(testId: String): List<StubMapping> = stubs.entries(testId)

  fun serveEvents(testId: String): List<ServeEvent> = serveEvents.entries(testId)

  fun clear(testId: String) {
    stubs.clear(testId)
    serveEvents.clear(testId)
  }

  fun clearAll() {
    stubs.clearAll()
    serveEvents.clearAll()
  }

  private fun ServeEvent.stoveTestId(): String? =
    request.headerMap().stoveTestId() ?: stubMapping?.stoveTestId()
}

/** The test that registered this stub, when it was registered inside a test context. */
internal fun StubMapping.stoveTestId(): String? =
  metadata
    ?.takeIf { it.containsKey(WireMockSystem.STOVE_TEST_ID_KEY) }
    ?.getString(WireMockSystem.STOVE_TEST_ID_KEY)

/** Request headers as a plain map for Stove's test-id extraction. */
internal fun LoggedRequest.headerMap(): Map<String, String> =
  headers
    .all()
    .associate { header -> header.key() to header.firstValue() }
