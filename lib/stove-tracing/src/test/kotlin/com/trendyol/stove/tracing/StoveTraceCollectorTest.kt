package com.trendyol.stove.tracing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class StoveTraceCollectorTest :
  FunSpec({

    test("registerTrace should create empty span list") {
      val collector = StoveTraceCollector()

      collector.registerTrace("trace-1", "test-1")

      collector.getTrace("trace-1").shouldBeEmpty()
      collector.getTestId("trace-1") shouldBe "test-1"
    }

    test("record should add span to the correct trace") {
      val collector = StoveTraceCollector()
      collector.registerTrace("trace-1", "test-1")
      val span = createSpan(traceId = "trace-1", spanId = "span-1")

      collector.record(span)

      collector.getTrace("trace-1") shouldHaveSize 1
      collector.getTrace("trace-1") shouldContain span
    }

    test("record should auto-create trace if not registered") {
      val collector = StoveTraceCollector()
      val span = createSpan(traceId = "trace-1", spanId = "span-1")

      collector.record(span)

      collector.getTrace("trace-1") shouldHaveSize 1
    }

    test("recordAll should add multiple spans") {
      val collector = StoveTraceCollector()
      val spans = listOf(
        createSpan(traceId = "trace-1", spanId = "span-1"),
        createSpan(traceId = "trace-1", spanId = "span-2"),
        createSpan(traceId = "trace-1", spanId = "span-3")
      )

      collector.recordAll(spans)

      collector.getTrace("trace-1") shouldHaveSize 3
    }

    test("getTraceTree should build span tree") {
      val collector = StoveTraceCollector()
      val rootSpan = createSpan(traceId = "trace-1", spanId = "root", parentSpanId = null)
      val childSpan = createSpan(traceId = "trace-1", spanId = "child", parentSpanId = "root")

      collector.record(rootSpan)
      collector.record(childSpan)

      val tree = collector.getTraceTree("trace-1")
      tree.shouldNotBeNull()
      tree.span.spanId shouldBe "root"
      tree.children shouldHaveSize 1
      tree.children[0].span.spanId shouldBe "child"
    }

    test("getTracesForTest should return all traces for a test") {
      val collector = StoveTraceCollector()
      collector.registerTrace("trace-1", "test-1")
      collector.registerTrace("trace-2", "test-1")
      collector.registerTrace("trace-3", "test-2")

      val traces = collector.getTracesForTest("test-1")

      traces shouldHaveSize 2
      traces shouldContain "trace-1"
      traces shouldContain "trace-2"
    }

    test("getFailedSpans should return only failed spans") {
      val collector = StoveTraceCollector()
      collector.record(createSpan(traceId = "trace-1", spanId = "ok-1", status = SpanStatus.OK))
      collector.record(createSpan(traceId = "trace-1", spanId = "error-1", status = SpanStatus.ERROR))
      collector.record(createSpan(traceId = "trace-1", spanId = "ok-2", status = SpanStatus.OK))

      val failed = collector.getFailedSpans("trace-1")

      failed shouldHaveSize 1
      failed[0].spanId shouldBe "error-1"
    }

    test("hasFailures should detect failures") {
      val collector = StoveTraceCollector()
      collector.record(createSpan(traceId = "trace-1", spanId = "ok-1", status = SpanStatus.OK))

      collector.hasFailures("trace-1") shouldBe false

      collector.record(createSpan(traceId = "trace-1", spanId = "error-1", status = SpanStatus.ERROR))

      collector.hasFailures("trace-1") shouldBe true
    }

    test("clear should remove trace data") {
      val collector = StoveTraceCollector()
      collector.registerTrace("trace-1", "test-1")
      collector.record(createSpan(traceId = "trace-1", spanId = "span-1"))

      collector.clear("trace-1")

      collector.getTrace("trace-1").shouldBeEmpty()
      collector.getTestId("trace-1").shouldBeNull()
    }

    test("clearForTest should remove all traces for a test") {
      val collector = StoveTraceCollector()
      collector.registerTrace("trace-1", "test-1")
      collector.registerTrace("trace-2", "test-1")
      collector.registerTrace("trace-3", "test-2")
      collector.record(createSpan(traceId = "trace-1", spanId = "span-1"))
      collector.record(createSpan(traceId = "trace-2", spanId = "span-2"))
      collector.record(createSpan(traceId = "trace-3", spanId = "span-3"))

      collector.clearForTest("test-1")

      collector.getTrace("trace-1").shouldBeEmpty()
      collector.getTrace("trace-2").shouldBeEmpty()
      collector.getTrace("trace-3") shouldHaveSize 1
    }

    test("clearAll should remove all data") {
      val collector = StoveTraceCollector()
      collector.registerTrace("trace-1", "test-1")
      collector.registerTrace("trace-2", "test-2")
      collector.record(createSpan(traceId = "trace-1", spanId = "span-1"))
      collector.record(createSpan(traceId = "trace-2", spanId = "span-2"))

      collector.clearAll()

      collector.traceCount() shouldBe 0
      collector.totalSpanCount() shouldBe 0
    }

    test("spanCount should return correct count for trace") {
      val collector = StoveTraceCollector()
      collector.record(createSpan(traceId = "trace-1", spanId = "span-1"))
      collector.record(createSpan(traceId = "trace-1", spanId = "span-2"))
      collector.record(createSpan(traceId = "trace-2", spanId = "span-3"))

      collector.spanCount("trace-1") shouldBe 2
      collector.spanCount("trace-2") shouldBe 1
      collector.spanCount("trace-3") shouldBe 0
    }

    test("totalSpanCount should return total across all traces") {
      val collector = StoveTraceCollector()
      collector.record(createSpan(traceId = "trace-1", spanId = "span-1"))
      collector.record(createSpan(traceId = "trace-1", spanId = "span-2"))
      collector.record(createSpan(traceId = "trace-2", spanId = "span-3"))

      collector.totalSpanCount() shouldBe 3
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
