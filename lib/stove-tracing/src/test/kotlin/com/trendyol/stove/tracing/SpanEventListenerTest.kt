package com.trendyol.stove.tracing

import com.trendyol.stove.reporting.SpanEventListener
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class SpanEventListenerTest :
  FunSpec({

    test("listener receives span events on record") {
      val collector = StoveTraceCollector()
      val received = mutableListOf<SpanInfo>()
      val listener = object : SpanEventListener {
        override fun onSpanRecorded(span: SpanInfo) {
          received.add(span)
        }
      }

      collector.addSpanListener(listener)
      val span = createSpan(traceId = "trace-1", spanId = "span-1")
      collector.record(span)

      received shouldHaveSize 1
      received[0].spanId shouldBe "span-1"
    }

    test("listener receives events from recordAll") {
      val collector = StoveTraceCollector()
      val received = mutableListOf<String>()
      val listener = object : SpanEventListener {
        override fun onSpanRecorded(span: SpanInfo) {
          received.add(span.spanId)
        }
      }

      collector.addSpanListener(listener)
      collector.recordAll(
        listOf(
          createSpan(traceId = "trace-1", spanId = "span-1"),
          createSpan(traceId = "trace-1", spanId = "span-2")
        )
      )

      received shouldBe listOf("span-1", "span-2")
    }

    test("throwing listener does not break collector or other listeners") {
      val collector = StoveTraceCollector()
      val received = mutableListOf<String>()

      collector.addSpanListener(object : SpanEventListener {
        override fun onSpanRecorded(span: SpanInfo) {
          error("boom")
        }
      })
      collector.addSpanListener(object : SpanEventListener {
        override fun onSpanRecorded(span: SpanInfo) {
          received.add(span.spanId)
        }
      })

      collector.record(createSpan(traceId = "trace-1", spanId = "span-1"))

      received shouldHaveSize 1
      received[0] shouldBe "span-1"
      // Verify the span was still recorded despite listener failure
      collector.getTrace("trace-1") shouldHaveSize 1
    }

    test("removed listener stops receiving events") {
      val collector = StoveTraceCollector()
      val received = mutableListOf<String>()
      val listener = object : SpanEventListener {
        override fun onSpanRecorded(span: SpanInfo) {
          received.add(span.spanId)
        }
      }

      collector.addSpanListener(listener)
      collector.record(createSpan(traceId = "trace-1", spanId = "span-1"))

      collector.removeSpanListener(listener)
      collector.record(createSpan(traceId = "trace-1", spanId = "span-2"))

      received shouldHaveSize 1
      received[0] shouldBe "span-1"
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
  status: SpanStatus = SpanStatus.OK
) = SpanInfo(
  traceId = traceId,
  spanId = spanId,
  parentSpanId = parentSpanId,
  operationName = operationName,
  serviceName = serviceName,
  startTimeNanos = startTimeNanos,
  endTimeNanos = endTimeNanos,
  status = status
)
