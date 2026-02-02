package com.trendyol.stove.tracing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class TraceVisualizationTest :
  FunSpec({

    context("TraceVisualization.from") {
      test("should create visualization from spans") {
        val spans = listOf(
          createSpan(spanId = "span1", operationName = "op1"),
          createSpan(spanId = "span2", operationName = "op2", parentSpanId = "span1")
        )

        val viz = TraceVisualization.from("trace-123", "test-1", spans)

        viz.traceId shouldBe "trace-123"
        viz.testId shouldBe "test-1"
        viz.totalSpans shouldBe 2
        viz.spans.size shouldBe 2
      }

      test("should count failed spans") {
        val spans = listOf(
          createSpan(spanId = "span1", status = SpanStatus.OK),
          createSpan(spanId = "span2", status = SpanStatus.ERROR),
          createSpan(spanId = "span3", status = SpanStatus.ERROR)
        )

        val viz = TraceVisualization.from("trace-123", "test-1", spans)

        viz.failedSpans shouldBe 2
      }

      test("should build tree representation") {
        val spans = listOf(
          createSpan(spanId = "root", operationName = "root-op"),
          createSpan(spanId = "child", operationName = "child-op", parentSpanId = "root")
        )

        val viz = TraceVisualization.from("trace-123", "test-1", spans)

        viz.tree shouldContain "root-op"
        viz.tree shouldContain "child-op"
      }

      test("should handle empty spans list") {
        val viz = TraceVisualization.from("trace-123", "test-1", emptyList())

        viz.totalSpans shouldBe 0
        viz.failedSpans shouldBe 0
        viz.spans shouldBe emptyList()
        viz.tree shouldBe "No spans in trace"
      }
    }

    context("VisualSpan.from") {
      test("should convert SpanInfo to VisualSpan") {
        val span = createSpan(
          spanId = "span-123",
          parentSpanId = "parent-456",
          operationName = "GET /api/test",
          serviceName = "test-service",
          startTimeNanos = 1000000L,
          endTimeNanos = 6000000L,
          status = SpanStatus.OK,
          attributes = mapOf("http.method" to "GET")
        )

        val visual = VisualSpan.from(span)

        visual.spanId shouldBe "span-123"
        visual.parentSpanId shouldBe "parent-456"
        visual.operationName shouldBe "GET /api/test"
        visual.serviceName shouldBe "test-service"
        visual.durationMs shouldBe 5.0
        visual.status shouldBe "OK"
        visual.attributes shouldBe mapOf("http.method" to "GET")
      }

      test("should calculate duration in milliseconds") {
        val span = createSpan(
          startTimeNanos = 0L,
          endTimeNanos = 10_000_000L // 10ms
        )

        val visual = VisualSpan.from(span)

        visual.durationMs shouldBe 10.0
      }

      test("should handle zero duration") {
        val span = createSpan(
          startTimeNanos = 1000L,
          endTimeNanos = 1000L
        )

        val visual = VisualSpan.from(span)

        visual.durationMs shouldBe 0.0
      }

      test("should handle sub-millisecond duration") {
        val span = createSpan(
          startTimeNanos = 0L,
          endTimeNanos = 500_000L // 0.5ms
        )

        val visual = VisualSpan.from(span)

        visual.durationMs shouldBe 0.5
      }

      test("should convert ERROR status") {
        val span = createSpan(status = SpanStatus.ERROR)

        val visual = VisualSpan.from(span)

        visual.status shouldBe "ERROR"
      }

      test("should convert UNSET status") {
        val span = createSpan(status = SpanStatus.UNSET)

        val visual = VisualSpan.from(span)

        visual.status shouldBe "UNSET"
      }

      test("should handle null parent span id") {
        val span = createSpan(parentSpanId = null)

        val visual = VisualSpan.from(span)

        visual.parentSpanId shouldBe null
      }

      test("should preserve empty attributes") {
        val span = createSpan(attributes = emptyMap())

        val visual = VisualSpan.from(span)

        visual.attributes shouldBe emptyMap()
      }

      test("should return zero duration for invalid end time") {
        val span = SpanInfo(
          traceId = "trace",
          spanId = "span",
          parentSpanId = null,
          operationName = "op",
          serviceName = "svc",
          startTimeNanos = 1000L,
          endTimeNanos = 0L, // Invalid: end before start
          status = SpanStatus.OK
        )

        val visual = VisualSpan.from(span)

        visual.durationMs shouldBe 0.0
      }

      test("should handle large duration values") {
        val span = createSpan(
          startTimeNanos = 0L,
          endTimeNanos = 60_000_000_000L // 60 seconds
        )

        val visual = VisualSpan.from(span)

        visual.durationMs shouldBe 60000.0
        visual.durationMs shouldBeGreaterThan 0.0
      }
    }
  })

private fun createSpan(
  traceId: String = "trace123",
  spanId: String = "span456",
  parentSpanId: String? = null,
  operationName: String = "test-operation",
  serviceName: String = "test-service",
  startTimeNanos: Long = 0L,
  endTimeNanos: Long = 1_000_000L,
  status: SpanStatus = SpanStatus.OK,
  attributes: Map<String, String> = emptyMap(),
  exception: ExceptionInfo? = null
): SpanInfo = SpanInfo(
  traceId = traceId,
  spanId = spanId,
  parentSpanId = parentSpanId,
  operationName = operationName,
  serviceName = serviceName,
  startTimeNanos = startTimeNanos,
  endTimeNanos = endTimeNanos,
  status = status,
  attributes = attributes,
  exception = exception
)
