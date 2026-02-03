package com.trendyol.stove.tracing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Duration.Companion.milliseconds

class TraceValidationTest :
  FunSpec({
    test("should validate spans and counts") {
      val collector = StoveTraceCollector()
      val traceId = "trace-1"
      collector.registerTrace(traceId, "test-1")

      collector.recordAll(
        listOf(
          span(
            traceId = traceId,
            spanId = "root",
            parentSpanId = null,
            operationName = "root-op",
            startTimeNanos = 0,
            endTimeNanos = 10_000_000,
            status = SpanStatus.OK
          ),
          span(
            traceId = traceId,
            spanId = "child",
            parentSpanId = "root",
            operationName = "child-op",
            startTimeNanos = 1_000_000,
            endTimeNanos = 5_000_000,
            status = SpanStatus.ERROR,
            attributes = mapOf("key" to "value")
          )
        )
      )

      val validation = TraceValidationDsl(collector, traceId)

      validation.shouldContainSpan("root")
      validation.shouldContainSpanMatching { it.operationName == "child-op" }
      validation.shouldHaveFailedSpan("child")
      validation.spanCountShouldBe(2)
      validation.spanCountShouldBeAtLeast(1)
      validation.spanCountShouldBeAtMost(2)
      validation.executionTimeShouldBeLessThan(20.milliseconds)
      validation.executionTimeShouldBeGreaterThan(5.milliseconds)
      validation.shouldHaveSpanWithAttribute("key", "value")
      validation.shouldHaveSpanWithAttributeContaining("key", "val")

      validation.getSpanCount() shouldBe 2
      validation.getFailedSpanCount() shouldBe 1
      validation.findSpanByName("root")?.operationName shouldBe "root-op"
      validation.getTotalDuration() shouldBe 10.milliseconds
      validation.spanTree() shouldNotBe null
      validation.renderTree() shouldNotBe "No spans in trace"
      validation.renderSummary() shouldNotBe "No spans in trace"
    }

    test("should fail when span expectations are not met") {
      val collector = StoveTraceCollector()
      val traceId = "trace-2"
      collector.registerTrace(traceId, "test-2")
      collector.record(
        span(
          traceId = traceId,
          spanId = "root",
          parentSpanId = null,
          operationName = "root-op",
          startTimeNanos = 0,
          endTimeNanos = 1_000_000,
          status = SpanStatus.OK
        )
      )

      val validation = TraceValidationDsl(collector, traceId)

      shouldThrow<IllegalArgumentException> {
        validation.shouldNotContainSpan("root")
      }

      shouldThrow<IllegalArgumentException> {
        validation.shouldHaveFailedSpan("root")
      }

      shouldThrow<IllegalArgumentException> {
        validation.executionTimeShouldBeGreaterThan(5.milliseconds)
      }
    }
  })

private fun span(
  traceId: String,
  spanId: String,
  parentSpanId: String?,
  operationName: String,
  startTimeNanos: Long,
  endTimeNanos: Long,
  status: SpanStatus,
  attributes: Map<String, String> = emptyMap()
): SpanInfo = SpanInfo(
  traceId = traceId,
  spanId = spanId,
  parentSpanId = parentSpanId,
  operationName = operationName,
  serviceName = "service",
  startTimeNanos = startTimeNanos,
  endTimeNanos = endTimeNanos,
  status = status,
  attributes = attributes
)
