package com.trendyol.stove.tracing

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@DslMarker
annotation class TracingDsl

@TracingDsl
class TraceValidationDsl(
  private val collector: StoveTraceCollector,
  private val traceId: String
) {
  private val trace: List<SpanInfo> by lazy { collector.getTrace(traceId) }
  private val tree: SpanNode? by lazy { collector.getTraceTree(traceId) }

  fun shouldContainSpan(operationName: String): TraceValidationDsl {
    require(trace.any { it.operationName.contains(operationName) }) {
      "Expected span containing '$operationName' but found: ${trace.map { it.operationName }}"
    }
    return this
  }

  fun shouldContainSpanMatching(predicate: (SpanInfo) -> Boolean): TraceValidationDsl {
    require(trace.any(predicate)) {
      "Expected span matching predicate but none found in: ${trace.map { it.operationName }}"
    }
    return this
  }

  fun shouldNotContainSpan(operationName: String): TraceValidationDsl {
    val matchingSpans = trace.filter { it.operationName.contains(operationName) }
    require(matchingSpans.isEmpty()) {
      "Expected no span containing '$operationName' but found: ${matchingSpans.map { it.operationName }}"
    }
    return this
  }

  fun shouldNotHaveFailedSpans(): TraceValidationDsl {
    val failed = trace.filter { it.isFailed }
    require(failed.isEmpty()) {
      val failureMessages = failed.map { span ->
        "${span.operationName}: ${span.exception?.message ?: "unknown error"}"
      }
      "Expected no failed spans but found: $failureMessages"
    }
    return this
  }

  fun shouldHaveFailedSpan(operationName: String): TraceValidationDsl {
    val failedSpans = trace.filter { it.isFailed }
    val failedMatching = failedSpans.filter { it.operationName.contains(operationName) }

    require(failedMatching.isNotEmpty()) {
      "Expected failed span containing '$operationName' but found failed spans: ${failedSpans.map { it.operationName }}"
    }
    return this
  }

  fun executionTimeShouldBeLessThan(duration: Duration): TraceValidationDsl {
    val totalDuration = calculateTotalDuration()
    require(totalDuration <= duration) {
      "Expected execution time <= $duration but was $totalDuration"
    }
    return this
  }

  fun executionTimeShouldBeGreaterThan(duration: Duration): TraceValidationDsl {
    val totalDuration = calculateTotalDuration()
    require(totalDuration >= duration) {
      "Expected execution time >= $duration but was $totalDuration"
    }
    return this
  }

  fun spanCountShouldBe(expected: Int): TraceValidationDsl {
    require(trace.size == expected) {
      "Expected $expected spans but found ${trace.size}"
    }
    return this
  }

  fun spanCountShouldBeAtLeast(minimum: Int): TraceValidationDsl {
    require(trace.size >= minimum) {
      "Expected at least $minimum spans but found ${trace.size}"
    }
    return this
  }

  fun spanCountShouldBeAtMost(maximum: Int): TraceValidationDsl {
    require(trace.size <= maximum) {
      "Expected at most $maximum spans but found ${trace.size}"
    }
    return this
  }

  fun shouldHaveSpanWithAttribute(key: String, value: String): TraceValidationDsl {
    require(trace.any { it.attributes[key] == value }) {
      "Expected span with attribute '$key'='$value' but none found"
    }
    return this
  }

  fun shouldHaveSpanWithAttributeContaining(key: String, substring: String): TraceValidationDsl {
    require(trace.any { it.attributes[key]?.contains(substring) == true }) {
      "Expected span with attribute '$key' containing '$substring' but none found"
    }
    return this
  }

  fun getSpanCount(): Int = trace.size

  fun getFailedSpans(): List<SpanInfo> = trace.filter { it.isFailed }

  fun getFailedSpanCount(): Int = getFailedSpans().size

  fun findSpan(predicate: (SpanInfo) -> Boolean): SpanInfo? = trace.find(predicate)

  fun findSpanByName(operationName: String): SpanInfo? =
    trace.find { it.operationName.contains(operationName) }

  fun spanTree(): SpanNode? = tree

  fun getTotalDuration(): Duration = calculateTotalDuration()

  private fun calculateTotalDuration(): Duration {
    if (trace.isEmpty()) return 0.milliseconds

    val minStart = trace.minOf { it.startTimeNanos }
    val maxEnd = trace.maxOf { it.endTimeNanos }
    val durationMs = (maxEnd - minStart) / TracingConstants.NANOS_TO_MILLIS

    return durationMs.milliseconds
  }

  fun renderTree(): String {
    val root = tree ?: return "No spans in trace"
    return TraceTreeRenderer.render(root)
  }

  fun renderSummary(): String {
    val root = tree ?: return "No spans in trace"
    return TraceTreeRenderer.renderSummary(root)
  }
}
