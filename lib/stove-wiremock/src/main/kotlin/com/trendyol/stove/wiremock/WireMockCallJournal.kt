package com.trendyol.stove.wiremock

import com.github.tomakehurst.wiremock.stubbing.ServeEvent
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import com.trendyol.stove.scoping.TestScopedJournal
import com.trendyol.stove.scoping.stoveTestId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Test-scoped journal of stubs and serve events, backed by the fail-open
 * [TestScopedJournal]: entries provably tagged with another test are excluded,
 * while untagged requests are visible to overlapping test lifecycle windows. Requests are attributed by their
 * `X-Stove-Test-Id` header or baggage first, then by the matched stub's tag.
 */
internal class WireMockCallJournal {
  private val stubs = TestScopedJournal<StubMapping>()
  private val serveEvents = TestScopedJournal<ServeEvent>()
  private val matchedStubIds = ConcurrentHashMap.newKeySet<UUID>()

  fun recordStub(stubMapping: StubMapping) {
    stubs.record(stubMapping.stoveTestId(), stubMapping)
  }

  fun record(serveEvent: ServeEvent) {
    if (serveEvent.wasMatched) serveEvent.stubMapping?.id?.let(matchedStubIds::add)
    serveEvents.record(serveEvent.stoveTestId(), serveEvent)
  }

  fun requests(testId: String): List<LoggedRequest> =
    serveEvents(testId).map { it.request }

  fun stubs(testId: String): List<StubMapping> = stubs.entries(testId)

  fun serveEvents(testId: String): List<ServeEvent> = serveEvents.entriesWithinTest(testId)

  /** Only stubs provably registered by the given test — the certainty view for warnings. */
  fun taggedStubs(testId: String): List<StubMapping> = stubs.taggedEntries(testId)

  /** Only serve events provably owned by the given test — the certainty view for warnings. */
  fun taggedServeEvents(testId: String): List<ServeEvent> = serveEvents.taggedEntries(testId)

  fun wasStubMatched(stubId: UUID): Boolean = stubId in matchedStubIds

  fun startTest(testId: String) {
    serveEvents.startTest(testId)
  }

  fun endTest(testId: String) {
    serveEvents.endTest(testId)
  }

  fun clear(testId: String) {
    stubs.taggedEntries(testId).forEach { matchedStubIds.remove(it.id) }
    stubs.clear(testId)
    serveEvents.clear(testId)
    serveEvents.pruneUntaggedOutsideWindows()
  }

  fun clearAll() {
    stubs.clearAll()
    serveEvents.clearAll()
    matchedStubIds.clear()
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
