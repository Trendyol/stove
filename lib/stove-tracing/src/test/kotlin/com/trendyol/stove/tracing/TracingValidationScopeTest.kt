package com.trendyol.stove.tracing

import arrow.core.None
import arrow.core.Some
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.milliseconds

class TracingValidationScopeTest :
  FunSpec({

    fun createScope(): Triple<StoveTraceCollector, TraceContext, TracingValidationScope> {
      val collector = StoveTraceCollector()
      val ctx = TraceContext.start("test-scope-1")
      collector.registerTrace(ctx.traceId, ctx.testId)

      collector.recordAll(
        listOf(
          SpanInfo(
            traceId = ctx.traceId,
            spanId = "root-span",
            parentSpanId = null,
            operationName = "root-op",
            serviceName = "test-service",
            startTimeNanos = 0,
            endTimeNanos = 10_000_000,
            status = SpanStatus.OK,
            attributes = mapOf("http.method" to "GET", "http.url" to "/api/users")
          ),
          SpanInfo(
            traceId = ctx.traceId,
            spanId = "child-span",
            parentSpanId = "root-span",
            operationName = "child-op",
            serviceName = "test-service",
            startTimeNanos = 1_000_000,
            endTimeNanos = 5_000_000,
            status = SpanStatus.ERROR,
            attributes = mapOf("db.system" to "postgresql")
          )
        )
      )

      val validation = TraceValidationDsl(collector, ctx.traceId)
      val scope = TracingValidationScope(ctx, validation, collector)
      return Triple(collector, ctx, scope)
    }

    test("should expose trace context properties") {
      val (_, ctx, scope) = createScope()

      scope.traceId shouldBe ctx.traceId
      scope.rootSpanId shouldBe ctx.rootSpanId
      scope.testId shouldBe ctx.testId
      TraceContext.clear()
    }

    test("toTraceparent should return W3C format") {
      val (_, ctx, scope) = createScope()

      val traceparent = scope.toTraceparent()
      traceparent shouldBe "00-${ctx.traceId}-${ctx.rootSpanId}-01"
      TraceContext.clear()
    }

    test("should delegate shouldContainSpan") {
      val (_, _, scope) = createScope()

      scope.shouldContainSpan("root")
      scope.shouldContainSpan("child")
      TraceContext.clear()
    }

    test("should delegate shouldContainSpanMatching") {
      val (_, _, scope) = createScope()

      scope.shouldContainSpanMatching { it.operationName == "child-op" }
      TraceContext.clear()
    }

    test("should delegate shouldNotContainSpan") {
      val (_, _, scope) = createScope()

      scope.shouldNotContainSpan("nonexistent")
      TraceContext.clear()
    }

    test("should delegate shouldNotHaveFailedSpans throws when there are failures") {
      val (_, _, scope) = createScope()

      try {
        scope.shouldNotHaveFailedSpans()
        throw AssertionError("Expected exception")
      } catch (e: IllegalArgumentException) {
        e.message shouldContain "failed spans"
      }
      TraceContext.clear()
    }

    test("should delegate shouldHaveFailedSpan") {
      val (_, _, scope) = createScope()

      scope.shouldHaveFailedSpan("child")
      TraceContext.clear()
    }

    test("should delegate executionTimeShouldBeLessThan") {
      val (_, _, scope) = createScope()

      scope.executionTimeShouldBeLessThan(20.milliseconds)
      TraceContext.clear()
    }

    test("should delegate executionTimeShouldBeGreaterThan") {
      val (_, _, scope) = createScope()

      scope.executionTimeShouldBeGreaterThan(5.milliseconds)
      TraceContext.clear()
    }

    test("should delegate spanCountShouldBe") {
      val (_, _, scope) = createScope()

      scope.spanCountShouldBe(2)
      TraceContext.clear()
    }

    test("should delegate spanCountShouldBeAtLeast") {
      val (_, _, scope) = createScope()

      scope.spanCountShouldBeAtLeast(1)
      TraceContext.clear()
    }

    test("should delegate spanCountShouldBeAtMost") {
      val (_, _, scope) = createScope()

      scope.spanCountShouldBeAtMost(5)
      TraceContext.clear()
    }

    test("should delegate shouldHaveSpanWithAttribute") {
      val (_, _, scope) = createScope()

      scope.shouldHaveSpanWithAttribute("http.method", "GET")
      TraceContext.clear()
    }

    test("should delegate shouldHaveSpanWithAttributeContaining") {
      val (_, _, scope) = createScope()

      scope.shouldHaveSpanWithAttributeContaining("http.url", "/api")
      TraceContext.clear()
    }

    test("should delegate getSpanCount") {
      val (_, _, scope) = createScope()

      scope.getSpanCount() shouldBe 2
      TraceContext.clear()
    }

    test("should delegate getFailedSpans") {
      val (_, _, scope) = createScope()

      scope.getFailedSpans().size shouldBe 1
      scope.getFailedSpans().first().operationName shouldBe "child-op"
      TraceContext.clear()
    }

    test("should delegate getFailedSpanCount") {
      val (_, _, scope) = createScope()

      scope.getFailedSpanCount() shouldBe 1
      TraceContext.clear()
    }

    test("should delegate findSpan returning Option") {
      val (_, _, scope) = createScope()

      scope.findSpan { it.operationName == "root-op" }.shouldBeInstanceOf<Some<SpanInfo>>()
      scope.findSpan { it.operationName == "nonexistent" } shouldBe None
      TraceContext.clear()
    }

    test("should delegate findSpanByName returning Option") {
      val (_, _, scope) = createScope()

      scope.findSpanByName("root").shouldBeInstanceOf<Some<SpanInfo>>()
      scope.findSpanByName("nonexistent") shouldBe None
      TraceContext.clear()
    }

    test("should delegate spanTree returning Option") {
      val (_, _, scope) = createScope()

      scope.spanTree().shouldBeInstanceOf<Some<SpanNode>>()
      TraceContext.clear()
    }

    test("should delegate getTotalDuration") {
      val (_, _, scope) = createScope()

      scope.getTotalDuration() shouldBe 10.milliseconds
      TraceContext.clear()
    }

    test("should delegate renderTree") {
      val (_, _, scope) = createScope()

      scope.renderTree() shouldNotBe "No spans in trace"
      scope.renderTree() shouldContain "root-op"
      TraceContext.clear()
    }

    test("should delegate renderSummary") {
      val (_, _, scope) = createScope()

      scope.renderSummary() shouldNotBe "No spans in trace"
      TraceContext.clear()
    }

    test("waitForSpans should return spans immediately when they already exist") {
      val (_, _, scope) = createScope()

      val spans = scope.waitForSpans(expectedCount = 2, timeoutMs = 1000)
      spans.size shouldBe 2
      TraceContext.clear()
    }

    test("getTraceVisualization should return visualization") {
      val (_, _, scope) = createScope()

      val viz = scope.getTraceVisualization()
      viz.traceId shouldBe scope.traceId
      viz.testId shouldBe scope.testId
      viz.totalSpans shouldBe 2
      viz.failedSpans shouldBe 1
      TraceContext.clear()
    }

    test("getAllTraceVisualizations should return all traces") {
      val (collector, ctx, scope) = createScope()

      // Add another trace
      val otherTraceId = "other-trace-id"
      collector.registerTrace(otherTraceId, "other-test")
      collector.record(
        SpanInfo(
          traceId = otherTraceId,
          spanId = "other-span",
          parentSpanId = null,
          operationName = "other-op",
          serviceName = "other-service",
          startTimeNanos = 0,
          endTimeNanos = 1_000_000,
          status = SpanStatus.OK
        )
      )

      val allViz = scope.getAllTraceVisualizations()
      allViz.size shouldBe 2
      TraceContext.clear()
    }
  })
