package com.trendyol.stove.tracing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.milliseconds

class TracingDslTest :
  FunSpec({

    test("shouldContainSpan should pass when span exists") {
      val collector = StoveTraceCollector()
      collector.record(createSpan(traceId = "t1", operationName = "OrderService.create"))

      val dsl = TraceValidationDsl(collector, "t1")

      dsl.shouldContainSpan("OrderService")
      dsl.shouldContainSpan("create")
    }

    test("shouldContainSpan should fail when span not found") {
      val collector = StoveTraceCollector()
      collector.record(createSpan(traceId = "t1", operationName = "OrderService.create"))

      val dsl = TraceValidationDsl(collector, "t1")

      val exception = shouldThrow<IllegalArgumentException> {
        dsl.shouldContainSpan("PaymentService")
      }
      exception.message shouldContain "Expected span containing 'PaymentService'"
    }

    test("shouldNotContainSpan should pass when span absent") {
      val collector = StoveTraceCollector()
      collector.record(createSpan(traceId = "t1", operationName = "OrderService.create"))

      val dsl = TraceValidationDsl(collector, "t1")

      dsl.shouldNotContainSpan("PaymentService")
    }

    test("shouldNotHaveFailedSpans should pass when no failures") {
      val collector = StoveTraceCollector()
      collector.record(createSpan(traceId = "t1", status = SpanStatus.OK))

      val dsl = TraceValidationDsl(collector, "t1")

      dsl.shouldNotHaveFailedSpans()
    }

    test("shouldNotHaveFailedSpans should fail when failures exist") {
      val collector = StoveTraceCollector()
      collector.record(
        createSpan(
          traceId = "t1",
          operationName = "failed.op",
          status = SpanStatus.ERROR,
          exception = ExceptionInfo("TestException", "test error")
        )
      )

      val dsl = TraceValidationDsl(collector, "t1")

      val exception = shouldThrow<IllegalArgumentException> {
        dsl.shouldNotHaveFailedSpans()
      }
      exception.message shouldContain "Expected no failed spans"
    }

    test("shouldHaveFailedSpan should pass when matching failure exists") {
      val collector = StoveTraceCollector()
      collector.record(
        createSpan(
          traceId = "t1",
          operationName = "PaymentService.charge",
          status = SpanStatus.ERROR
        )
      )

      val dsl = TraceValidationDsl(collector, "t1")

      dsl.shouldHaveFailedSpan("PaymentService")
    }

    test("executionTimeShouldBeLessThan should validate duration") {
      val collector = StoveTraceCollector()
      collector.record(
        createSpan(
          traceId = "t1",
          startTimeNanos = 0,
          endTimeNanos = 50_000_000 // 50ms
        )
      )

      val dsl = TraceValidationDsl(collector, "t1")

      dsl.executionTimeShouldBeLessThan(100.milliseconds)

      shouldThrow<IllegalArgumentException> {
        dsl.executionTimeShouldBeLessThan(10.milliseconds)
      }
    }

    test("spanCountShouldBe should validate exact count") {
      val collector = StoveTraceCollector()
      collector.record(createSpan(traceId = "t1", spanId = "s1"))
      collector.record(createSpan(traceId = "t1", spanId = "s2"))

      val dsl = TraceValidationDsl(collector, "t1")

      dsl.spanCountShouldBe(2)
    }

    test("shouldHaveSpanWithAttribute should find attribute") {
      val collector = StoveTraceCollector()
      collector.record(
        createSpan(
          traceId = "t1",
          attributes = mapOf("db.system" to "postgresql")
        )
      )

      val dsl = TraceValidationDsl(collector, "t1")

      dsl.shouldHaveSpanWithAttribute("db.system", "postgresql")
    }

    test("findSpanByName should locate span") {
      val collector = StoveTraceCollector()
      collector.record(createSpan(traceId = "t1", operationName = "OrderService.create"))

      val dsl = TraceValidationDsl(collector, "t1")

      val found = dsl.findSpanByName("OrderService")
      found.shouldNotBeNull()
      found.operationName shouldBe "OrderService.create"
    }

    test("spanTree should return span tree") {
      val collector = StoveTraceCollector()
      collector.record(createSpan(traceId = "t1", spanId = "root", parentSpanId = null))
      collector.record(createSpan(traceId = "t1", spanId = "child", parentSpanId = "root"))

      val dsl = TraceValidationDsl(collector, "t1")

      val tree = dsl.spanTree()
      tree.shouldNotBeNull()
      tree.spanCount shouldBe 2
    }

    test("renderTree should produce output") {
      val collector = StoveTraceCollector()
      collector.record(createSpan(traceId = "t1", spanId = "root", operationName = "root.op"))

      val dsl = TraceValidationDsl(collector, "t1")

      val rendered = dsl.renderTree()
      rendered shouldContain "root.op"
    }

    test("tracing DSL should configure options") {
      val options = TracingOptions().apply {
        enabled()
        maxSpansPerTrace(500)
        serviceName("my-app")
        enableSpanReceiver(port = 4318)
      }

      options.enabled shouldBe true
      options.maxSpansPerTrace shouldBe 500
      options.serviceName shouldBe "my-app"
      options.spanReceiverEnabled shouldBe true
      options.spanReceiverPort shouldBe 4318
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
