package com.trendyol.stove.tracing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class TraceTreeRendererTest :
  FunSpec({

    test("render should show operation name and duration") {
      val span = createSpan(
        spanId = "root",
        operationName = "OrderService.createOrder",
        startTimeNanos = 0,
        endTimeNanos = 100_000_000
      )
      val tree = SpanNode(span)

      val output = TraceTreeRenderer.render(tree)

      output shouldContain "OrderService.createOrder"
      output shouldContain "[100ms]"
    }

    test("render should show checkmark for successful span") {
      val span = createSpan(status = SpanStatus.OK)
      val tree = SpanNode(span)

      val output = TraceTreeRenderer.render(tree)

      output shouldContain "✓"
      output shouldNotContain "✗"
    }

    test("render should show X for failed span") {
      val span = createSpan(status = SpanStatus.ERROR)
      val tree = SpanNode(span)

      val output = TraceTreeRenderer.render(tree)

      output shouldContain "✗"
    }

    test("render should mark failure point") {
      val span = createSpan(
        status = SpanStatus.ERROR,
        exception = ExceptionInfo("RuntimeException", "Something went wrong", listOf("at Test.kt:10"))
      )
      val tree = SpanNode(span)

      val output = TraceTreeRenderer.render(tree)

      output shouldContain "◄── FAILURE POINT"
      output shouldContain "Error: RuntimeException: Something went wrong"
    }

    test("render should show relevant attributes") {
      val span = createSpan(
        attributes = mapOf(
          "db.system" to "postgresql",
          "db.statement" to "SELECT * FROM users",
          "internal.flag" to "true"
        )
      )
      val tree = SpanNode(span)

      val output = TraceTreeRenderer.render(tree, includeAttributes = true)

      output shouldContain "db.system: postgresql"
      output shouldContain "db.statement: SELECT * FROM users"
      output shouldNotContain "internal.flag"
    }

    test("render should show nested hierarchy") {
      val grandchild = SpanNode(createSpan(spanId = "grandchild", operationName = "Repository.save"))
      val child = SpanNode(createSpan(spanId = "child", operationName = "Service.process"), listOf(grandchild))
      val root = SpanNode(createSpan(spanId = "root", operationName = "Controller.handle"), listOf(child))

      val output = TraceTreeRenderer.render(root)

      output shouldContain "Controller.handle"
      output shouldContain "Service.process"
      output shouldContain "Repository.save"
    }

    test("renderCompact should produce condensed output") {
      val child = SpanNode(createSpan(operationName = "child.op", startTimeNanos = 0, endTimeNanos = 30_000_000))
      val root =
        SpanNode(createSpan(operationName = "root.op", startTimeNanos = 0, endTimeNanos = 50_000_000), listOf(child))

      val output = TraceTreeRenderer.renderCompact(root)

      output shouldContain "✓ root.op (50ms)"
      output shouldContain "✓ child.op (30ms)"
    }

    test("renderSummary should show statistics") {
      val failedChild = SpanNode(
        createSpan(
          spanId = "child",
          startTimeNanos = 10_000_000,
          endTimeNanos = 50_000_000,
          status = SpanStatus.ERROR,
          exception = ExceptionInfo("TestException", "Test error")
        )
      )
      val root = SpanNode(
        createSpan(
          spanId = "root",
          startTimeNanos = 0,
          endTimeNanos = 100_000_000,
          status = SpanStatus.OK
        ),
        listOf(failedChild)
      )

      val output = TraceTreeRenderer.renderSummary(root)

      output shouldContain "Total spans: 2"
      output shouldContain "Failed spans: 1"
      output shouldContain "Total duration: 100ms"
      output shouldContain "Max depth: 2"
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
