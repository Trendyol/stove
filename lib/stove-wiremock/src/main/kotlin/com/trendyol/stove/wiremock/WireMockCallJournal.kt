package com.trendyol.stove.wiremock

import com.github.tomakehurst.wiremock.stubbing.ServeEvent
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import com.trendyol.stove.messaging.kafka.stoveTestId
import java.util.concurrent.*

/**
 * Test-scoped journal of stubs and serve events.
 *
 * Scoping is fail-open: an entry is excluded from a test's view only when it is
 * provably tagged with a different test id. Untagged entries — stubs registered
 * outside any test context, and requests carrying neither the `X-Stove-Test-Id`
 * header nor test-id baggage — are visible to every test, so applications without
 * test-id propagation behave as if scoping did not exist.
 */
internal class WireMockCallJournal {
  private val stubsByTestId = ConcurrentHashMap<String, CopyOnWriteArrayList<StubMapping>>()
  private val serveEventsByTestId = ConcurrentHashMap<String, CopyOnWriteArrayList<ServeEvent>>()
  private val untaggedStubs = CopyOnWriteArrayList<StubMapping>()
  private val untaggedServeEvents = CopyOnWriteArrayList<ServeEvent>()

  fun recordStub(stubMapping: StubMapping) {
    when (val testId = stubMapping.stoveTestId()) {
      null -> untaggedStubs.add(stubMapping)
      else -> stubsByTestId.computeIfAbsent(testId) { CopyOnWriteArrayList() }.add(stubMapping)
    }
  }

  fun record(serveEvent: ServeEvent) {
    when (val testId = serveEvent.stoveTestId()) {
      null -> untaggedServeEvents.add(serveEvent)
      else -> serveEventsByTestId.computeIfAbsent(testId) { CopyOnWriteArrayList() }.add(serveEvent)
    }
  }

  fun requests(testId: String): List<LoggedRequest> =
    serveEvents(testId).map { it.request }

  fun stubs(testId: String): List<StubMapping> =
    untaggedStubs.toList() + (stubsByTestId[testId]?.toList() ?: emptyList())

  fun serveEvents(testId: String): List<ServeEvent> =
    untaggedServeEvents.toList() + (serveEventsByTestId[testId]?.toList() ?: emptyList())

  fun clear(testId: String) {
    stubsByTestId.remove(testId)
    serveEventsByTestId.remove(testId)
  }

  fun clearAll() {
    stubsByTestId.clear()
    serveEventsByTestId.clear()
    untaggedStubs.clear()
    untaggedServeEvents.clear()
  }

  private fun ServeEvent.stoveTestId(): String? =
    request.headerMap().stoveTestId() ?: stubMapping?.stoveTestId()

  private fun StubMapping.stoveTestId(): String? =
    metadata
      ?.takeIf { it.containsKey(WireMockSystem.STOVE_TEST_ID_KEY) }
      ?.getString(WireMockSystem.STOVE_TEST_ID_KEY)
}

/** Request headers as a plain map for Stove's test-id extraction. */
internal fun LoggedRequest.headerMap(): Map<String, String> =
  headers
    .all()
    .associate { header -> header.key() to header.firstValue() }
