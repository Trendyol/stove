package com.trendyol.stove.tracing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SpanInfoTest :
  FunSpec({

    test("durationMs should calculate correctly") {
      val span = createSpan(
        startTimeNanos = 1_000_000_000L,
        endTimeNanos = 1_500_000_000L
      )

      span.durationMs shouldBe 500L
    }

    test("durationNanos should return correct value") {
      val span = createSpan(
        startTimeNanos = 1_000_000_000L,
        endTimeNanos = 1_500_000_000L
      )

      span.durationNanos shouldBe 500_000_000L
    }

    test("isFailed should return true for ERROR status") {
      val span = createSpan(status = SpanStatus.ERROR)

      span.isFailed shouldBe true
      span.isSuccess shouldBe false
    }

    test("isSuccess should return true for OK status") {
      val span = createSpan(status = SpanStatus.OK)

      span.isSuccess shouldBe true
      span.isFailed shouldBe false
    }

    test("UNSET status should not be failed or success") {
      val span = createSpan(status = SpanStatus.UNSET)

      span.isFailed shouldBe false
      span.isSuccess shouldBe false
    }
  })

private fun createSpan(
  traceId: String = "trace123",
  spanId: String = "span123",
  parentSpanId: String? = null,
  operationName: String = "test.operation",
  serviceName: String = "test-service",
  startTimeNanos: Long = 1_000_000_000L,
  endTimeNanos: Long = 1_100_000_000L,
  status: SpanStatus = SpanStatus.OK,
  attributes: Map<String, String> = emptyMap(),
  exception: ExceptionInfo? = null
) = SpanInfo(
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
