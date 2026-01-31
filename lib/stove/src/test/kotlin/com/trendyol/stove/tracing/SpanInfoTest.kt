package com.trendyol.stove.tracing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SpanInfoTest :
  FunSpec({

    test("durationMs should calculate milliseconds from nanoseconds") {
      val span = createSpan(
        startTimeNanos = 0L,
        endTimeNanos = 5_000_000L // 5ms in nanoseconds
      )

      span.durationMs shouldBe 5L
    }

    test("durationMs should handle zero duration") {
      val span = createSpan(
        startTimeNanos = 1000L,
        endTimeNanos = 1000L
      )

      span.durationMs shouldBe 0L
    }

    test("durationNanos should return raw nanosecond difference") {
      val span = createSpan(
        startTimeNanos = 100L,
        endTimeNanos = 500L
      )

      span.durationNanos shouldBe 400L
    }

    test("isFailed should return true when status is ERROR") {
      val span = createSpan(status = SpanStatus.ERROR)

      span.isFailed shouldBe true
      span.isSuccess shouldBe false
    }

    test("isSuccess should return true when status is OK") {
      val span = createSpan(status = SpanStatus.OK)

      span.isSuccess shouldBe true
      span.isFailed shouldBe false
    }

    test("UNSET status should be neither failed nor success") {
      val span = createSpan(status = SpanStatus.UNSET)

      span.isFailed shouldBe false
      span.isSuccess shouldBe false
    }

    test("span with exception should preserve exception info") {
      val exception = ExceptionInfo(
        type = "java.lang.RuntimeException",
        message = "Something went wrong",
        stackTrace = listOf("at com.example.Test.method(Test.kt:10)")
      )
      val span = createSpan(exception = exception)

      span.exception shouldBe exception
      span.exception?.type shouldBe "java.lang.RuntimeException"
      span.exception?.message shouldBe "Something went wrong"
      span.exception?.stackTrace?.size shouldBe 1
    }

    test("span without exception should have null exception") {
      val span = createSpan()

      span.exception shouldBe null
    }

    test("span should preserve attributes") {
      val attrs = mapOf(
        "http.method" to "GET",
        "http.url" to "/api/test"
      )
      val span = createSpan(attributes = attrs)

      span.attributes shouldBe attrs
      span.attributes["http.method"] shouldBe "GET"
    }

    test("span with empty attributes should have empty map") {
      val span = createSpan(attributes = emptyMap())

      span.attributes shouldBe emptyMap()
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
