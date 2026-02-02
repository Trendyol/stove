package com.trendyol.stove.tracing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class TraceTreeRendererTest :
  FunSpec({

    context("render") {
      test("should render single node with operation name and duration") {
        val node = SpanNode(createSpan(operationName = "GET /api/test", durationMs = 100))

        val result = TraceTreeRenderer.render(node)

        result shouldContain "GET /api/test"
        result shouldContain "[100ms]"
        result shouldContain "✓"
      }

      test("should render failed span with failure marker") {
        val node = SpanNode(createSpan(status = SpanStatus.ERROR))

        val result = TraceTreeRenderer.render(node)

        result shouldContain "✗"
        result shouldContain "FAILURE POINT"
      }

      test("should render parent-child hierarchy") {
        val child = SpanNode(createSpan(spanId = "child", operationName = "child-op"))
        val parent = SpanNode(createSpan(spanId = "parent", operationName = "parent-op"), listOf(child))

        val result = TraceTreeRenderer.render(parent)

        result shouldContain "parent-op"
        result shouldContain "child-op"
      }

      test("should render all children") {
        val child1 = SpanNode(createSpan(spanId = "child1", operationName = "first-child"))
        val child2 = SpanNode(createSpan(spanId = "child2", operationName = "second-child"))
        val parent = SpanNode(createSpan(spanId = "parent", operationName = "parent-op"), listOf(child1, child2))

        val result = TraceTreeRenderer.render(parent)

        result shouldContain "parent-op"
        result shouldContain "first-child"
        result shouldContain "second-child"
      }

      test("should render exception info for failed span") {
        val exception = ExceptionInfo(
          type = "RuntimeException",
          message = "Something failed",
          stackTrace = listOf("at Test.method(Test.kt:10)")
        )
        val node = SpanNode(createSpan(status = SpanStatus.ERROR, exception = exception))

        val result = TraceTreeRenderer.render(node)

        result shouldContain "Error: RuntimeException: Something failed"
        result shouldContain "at Test.method(Test.kt:10)"
      }

      test("should render relevant attributes when enabled") {
        val node = SpanNode(
          createSpan(
            attributes = mapOf(
              "http.method" to "POST",
              "http.url" to "/api/users",
              "custom.attr" to "ignored"
            )
          )
        )

        val result = TraceTreeRenderer.render(node, includeAttributes = true)

        result shouldContain "http.method: POST"
        result shouldContain "http.url: /api/users"
        result shouldNotContain "custom.attr"
      }

      test("should not render attributes when disabled") {
        val node = SpanNode(
          createSpan(
            attributes = mapOf("http.method" to "GET")
          )
        )

        val result = TraceTreeRenderer.render(node, includeAttributes = false)

        result shouldNotContain "http.method"
      }

      test("should use custom attribute prefixes") {
        val node = SpanNode(
          createSpan(
            attributes = mapOf(
              "custom.key" to "value",
              "http.method" to "GET"
            )
          )
        )

        val result = TraceTreeRenderer.render(
          node,
          includeAttributes = true,
          attributePrefixes = listOf("custom.")
        )

        result shouldContain "custom.key: value"
        result shouldNotContain "http.method"
      }

      test("should mark deepest failure point only") {
        val deepFailed = SpanNode(createSpan(spanId = "deep", status = SpanStatus.ERROR))
        val middleFailed = SpanNode(createSpan(spanId = "middle", status = SpanStatus.ERROR), listOf(deepFailed))
        val parent = SpanNode(createSpan(spanId = "parent", status = SpanStatus.OK), listOf(middleFailed))

        val result = TraceTreeRenderer.render(parent)

        // Only the deepest failure should have the marker
        val lines = result.lines()
        val markerCount = lines.count { it.contains("FAILURE POINT") }
        markerCount == 1
      }
    }

    context("renderColored") {
      test("should include ANSI color codes for failed spans") {
        val node = SpanNode(createSpan(status = SpanStatus.ERROR, operationName = "failed-op"))

        val result = TraceTreeRenderer.renderColored(node)

        // Should contain ANSI escape codes
        result shouldContain "\u001B["
        result shouldContain "failed-op"
        result shouldContain "✗"
        result shouldContain "FAILURE POINT"
      }

      test("should color success spans green") {
        val node = SpanNode(createSpan(status = SpanStatus.OK, operationName = "success-op"))

        val result = TraceTreeRenderer.renderColored(node)

        // Should contain bright green color code for checkmark
        result shouldContain "\u001B[92m✓"
      }

      test("should color failure marker in bold yellow") {
        val node = SpanNode(createSpan(status = SpanStatus.ERROR))

        val result = TraceTreeRenderer.renderColored(node)

        // Should contain bold + bright yellow for failure marker
        result shouldContain "\u001B[1m\u001B[93m◄── FAILURE POINT"
      }

      test("should color exception info with red and yellow") {
        val exception = ExceptionInfo(
          type = "RuntimeException",
          message = "Test error",
          stackTrace = listOf("at Test.method(Test.kt:10)")
        )
        val node = SpanNode(createSpan(status = SpanStatus.ERROR, exception = exception))

        val result = TraceTreeRenderer.renderColored(node)

        // Exception type should be yellow
        result shouldContain "\u001B[33mRuntimeException"
      }
    }

    context("renderCompact") {
      test("should render compact format with indentation") {
        val child = SpanNode(createSpan(spanId = "child", operationName = "child-op", durationMs = 50))
        val parent =
          SpanNode(createSpan(spanId = "parent", operationName = "parent-op", durationMs = 100), listOf(child))

        val result = TraceTreeRenderer.renderCompact(parent)

        result shouldContain "✓ parent-op (100ms)"
        result shouldContain "  ✓ child-op (50ms)"
      }

      test("should show failure status in compact format") {
        val node = SpanNode(createSpan(status = SpanStatus.ERROR, operationName = "failed-op"))

        val result = TraceTreeRenderer.renderCompact(node)

        result shouldContain "✗ failed-op"
      }
    }

    context("renderSummary") {
      test("should render trace summary with counts") {
        val child = SpanNode(createSpan(spanId = "child"))
        val parent = SpanNode(createSpan(spanId = "parent", durationMs = 200), listOf(child))

        val result = TraceTreeRenderer.renderSummary(parent)

        result shouldContain "Trace Summary:"
        result shouldContain "Total spans: 2"
        result shouldContain "Failed spans: 0"
        result shouldContain "Total duration: 200ms"
        result shouldContain "Max depth: 2"
      }

      test("should include failure point info when failures exist") {
        val exception = ExceptionInfo(
          type = "TestException",
          message = "Test error"
        )
        val failed = SpanNode(
          createSpan(
            spanId = "failed",
            operationName = "failed-operation",
            status = SpanStatus.ERROR,
            exception = exception
          )
        )
        val parent = SpanNode(createSpan(spanId = "parent"), listOf(failed))

        val result = TraceTreeRenderer.renderSummary(parent)

        result shouldContain "Failed spans: 1"
        result shouldContain "Failure point: failed-operation"
        result shouldContain "Error: TestException: Test error"
      }

      test("should not include failure info when no failures") {
        val node = SpanNode(createSpan(status = SpanStatus.OK))

        val result = TraceTreeRenderer.renderSummary(node)

        result shouldNotContain "Failure point"
        result shouldNotContain "Error:"
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
  durationMs: Long = 1L,
  status: SpanStatus = SpanStatus.OK,
  attributes: Map<String, String> = emptyMap(),
  exception: ExceptionInfo? = null
): SpanInfo {
  val actualEndTime = if (durationMs != 1L) {
    startTimeNanos + (durationMs * 1_000_000L)
  } else {
    endTimeNanos
  }
  return SpanInfo(
    traceId = traceId,
    spanId = spanId,
    parentSpanId = parentSpanId,
    operationName = operationName,
    serviceName = serviceName,
    startTimeNanos = startTimeNanos,
    endTimeNanos = actualEndTime,
    status = status,
    attributes = attributes,
    exception = exception
  )
}
