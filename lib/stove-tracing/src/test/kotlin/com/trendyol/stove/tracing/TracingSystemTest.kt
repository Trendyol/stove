package com.trendyol.stove.tracing

import arrow.core.getOrElse
import com.trendyol.stove.system.Stove
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking

class TracingSystemTest :
  FunSpec({
    test("ensureTraceStarted registers context and validation") {
      TraceContext.clear()
      val stove = Stove()
      val system = TracingSystem(stove, TracingSystemOptions(TracingOptions().enabled()))

      val ctx = runBlocking { system.ensureTraceStarted() }

      TraceContext.current() shouldNotBe null
      system.validation(ctx.traceId).getSpanCount() shouldBe 0
      system.collector.traceCount() shouldBe 1

      system.endTrace()
      TraceContext.current() shouldBe null
    }

    test("getTraceVisualizationForCurrentTest returns visualization for current trace") {
      TraceContext.clear()
      val stove = Stove()
      val system = TracingSystem(stove, TracingSystemOptions(TracingOptions().enabled()))

      val ctx = runBlocking { system.ensureTraceStarted() }

      system.collector.record(
        span(
          traceId = ctx.traceId,
          spanId = "root",
          parentSpanId = null,
          operationName = "root",
          startTimeNanos = 0,
          endTimeNanos = 1_000_000,
          status = SpanStatus.OK
        )
      )

      val visualization = system.getTraceVisualizationForCurrentTest(waitTimeMs = 10)
      visualization.getOrElse { null }?.traceId shouldBe ctx.traceId
    }

    test("getTraceVisualizationForCurrentTest falls back to most recent trace") {
      TraceContext.clear()
      val stove = Stove()
      val system = TracingSystem(stove, TracingSystemOptions(TracingOptions().enabled()))

      runBlocking { system.ensureTraceStarted() }

      system.collector.record(
        span(
          traceId = "other-trace",
          spanId = "root",
          parentSpanId = null,
          operationName = "root",
          startTimeNanos = 10,
          endTimeNanos = 20,
          status = SpanStatus.OK
        )
      )

      val visualization = system.getTraceVisualizationForCurrentTest(waitTimeMs = 1)
      visualization.getOrElse { null }?.traceId shouldBe "other-trace"
    }

    test("stop clears traces and context") {
      TraceContext.clear()
      val stove = Stove()
      val system = TracingSystem(stove, TracingSystemOptions(TracingOptions().enabled()))

      runBlocking { system.ensureTraceStarted() }
      system.collector.record(
        span(
          traceId = TraceContext.current()!!.traceId,
          spanId = "root",
          parentSpanId = null,
          operationName = "root",
          startTimeNanos = 0,
          endTimeNanos = 1,
          status = SpanStatus.OK
        )
      )

      runBlocking { system.stop() }

      TraceContext.current() shouldBe null
      system.collector.traceCount() shouldBe 0
    }
  })

private fun span(
  traceId: String,
  spanId: String,
  parentSpanId: String?,
  operationName: String,
  startTimeNanos: Long,
  endTimeNanos: Long,
  status: SpanStatus
): SpanInfo = SpanInfo(
  traceId = traceId,
  spanId = spanId,
  parentSpanId = parentSpanId,
  operationName = operationName,
  serviceName = "service",
  startTimeNanos = startTimeNanos,
  endTimeNanos = endTimeNanos,
  status = status
)
