package com.trendyol.stove.wiremock

import com.github.tomakehurst.wiremock.stubbing.ServeEvent
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import com.trendyol.stove.tracing.TraceContext
import java.util.concurrent.*

internal class WireMockCallJournal {
  private val stubsByTestId = ConcurrentHashMap<String, CopyOnWriteArrayList<StubMapping>>()
  private val serveEventsByTestId = ConcurrentHashMap<String, CopyOnWriteArrayList<ServeEvent>>()

  fun recordStub(stubMapping: StubMapping) {
    val testId = stubMapping.stoveTestId() ?: return
    stubsByTestId.computeIfAbsent(testId) { CopyOnWriteArrayList() }.add(stubMapping)
  }

  fun record(serveEvent: ServeEvent) {
    val testId = serveEvent.stoveTestId() ?: return
    serveEventsByTestId.computeIfAbsent(testId) { CopyOnWriteArrayList() }.add(serveEvent)
  }

  fun requests(testId: String): List<LoggedRequest> =
    serveEvents(testId).map { it.request }

  fun stubs(testId: String): List<StubMapping> =
    stubsByTestId[testId]?.toList() ?: emptyList()

  fun serveEvents(testId: String): List<ServeEvent> =
    serveEventsByTestId[testId]?.toList() ?: emptyList()

  fun clear(testId: String) {
    stubsByTestId.remove(testId)
    serveEventsByTestId.remove(testId)
  }

  fun clearAll() {
    stubsByTestId.clear()
    serveEventsByTestId.clear()
  }

  private fun ServeEvent.stoveTestId(): String? =
    stubMapping?.metadata?.getString(WireMockSystem.STOVE_TEST_ID_KEY)
      ?: request.getHeader(TraceContext.STOVE_TEST_ID_HEADER)

  private fun StubMapping.stoveTestId(): String? =
    metadata?.getString(WireMockSystem.STOVE_TEST_ID_KEY)
}
