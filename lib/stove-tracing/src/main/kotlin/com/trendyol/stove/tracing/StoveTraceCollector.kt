package com.trendyol.stove.tracing

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class StoveTraceCollector {
  private val spans = ConcurrentHashMap<String, CopyOnWriteArrayList<SpanInfo>>()
  private val traceToTest = ConcurrentHashMap<String, String>()

  fun registerTrace(traceId: String, testId: String) {
    traceToTest[traceId] = testId
    spans.computeIfAbsent(traceId) { CopyOnWriteArrayList() }
  }

  fun record(span: SpanInfo) {
    spans.computeIfAbsent(span.traceId) { CopyOnWriteArrayList() }.add(span)
  }

  fun recordAll(spansToRecord: Collection<SpanInfo>) {
    spansToRecord.forEach { record(it) }
  }

  fun getTrace(traceId: String): List<SpanInfo> =
    spans[traceId]?.toList() ?: emptyList()

  fun getTraceTree(traceId: String): SpanNode? =
    SpanTree.build(getTrace(traceId))

  fun getTracesForTest(testId: String): List<String> =
    traceToTest.filterValues { it == testId }.keys.toList()

  fun getTestId(traceId: String): String? =
    traceToTest[traceId]

  fun getAllTraces(): Map<String, List<SpanInfo>> =
    spans.mapValues { it.value.toList() }

  fun getFailedSpans(traceId: String): List<SpanInfo> =
    getTrace(traceId).filter { it.isFailed }

  fun hasFailures(traceId: String): Boolean =
    getTrace(traceId).any { it.isFailed }

  fun clear(traceId: String) {
    spans.remove(traceId)
    traceToTest.remove(traceId)
  }

  fun clearForTest(testId: String) {
    val traceIds = getTracesForTest(testId)
    traceIds.forEach { clear(it) }
  }

  fun clearAll() {
    spans.clear()
    traceToTest.clear()
  }

  fun spanCount(traceId: String): Int =
    spans[traceId]?.size ?: 0

  fun totalSpanCount(): Int =
    spans.values.sumOf { it.size }

  fun traceCount(): Int = spans.size

  /**
   * Waits for at least the expected number of spans to be collected.
   * Inspired by beholder-otel-extension's TestSpanCollector.
   *
   * @param traceId the trace ID to wait for
   * @param expectedCount minimum number of spans to wait for
   * @param timeoutMs maximum wait time in milliseconds
   * @return the collected spans for the trace
   */
  fun waitForSpans(
    traceId: String,
    expectedCount: Int,
    timeoutMs: Long = TracingConstants.DEFAULT_SPAN_WAIT_TIMEOUT_MS
  ): List<SpanInfo> {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val currentSpans = getTrace(traceId)
      if (currentSpans.size >= expectedCount) {
        return currentSpans
      }
      try {
        Thread.sleep(TracingConstants.DEFAULT_SPAN_POLL_INTERVAL_MS)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        break
      }
    }
    return getTrace(traceId)
  }
}
