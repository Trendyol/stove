package com.trendyol.stove.tracing

import com.trendyol.stove.reporting.ReportEntry
import com.trendyol.stove.reporting.StoveTestContext
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.ApplicationUnderTest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking

class TraceReportBuilderTest :
  FunSpec({
    test("buildFullReport includes stove report and trace tree") {
      val stove = Stove()
      stove.applicationUnderTest(NoOpApplicationUnderTest())

      val tracingSystem = TracingSystem(stove, TracingSystemOptions(TracingOptions().enabled()))
      stove.getOrRegister(tracingSystem)

      runBlocking { stove.run() }

      val reporter = Stove.reporter()
      reporter.startTest(StoveTestContext("test-id", "test-name", "spec"))
      reporter.record(
        ReportEntry.failure(
          system = "Test",
          testId = "test-id",
          action = "failure",
          error = "boom"
        )
      )

      val ctx = TraceContext.start("test-id")
      tracingSystem.collector.registerTrace(ctx.traceId, ctx.testId)
      tracingSystem.collector.record(
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

      val report = TraceReportBuilder.buildFullReport()

      report shouldContain "STOVE TEST EXECUTION REPORT"
      report shouldContain "EXECUTION TRACE"

      TraceContext.clear()
      stove.close()
    }

    test("buildFullReport returns empty when no failures and no trace") {
      val stove = Stove()
      stove.applicationUnderTest(NoOpApplicationUnderTest())

      runBlocking { stove.run() }

      val report = TraceReportBuilder.buildFullReport()

      report shouldBe ""

      stove.close()
    }
  })

private class NoOpApplicationUnderTest : ApplicationUnderTest<String> {
  override suspend fun start(configurations: List<String>): String = "context"

  override suspend fun stop() = Unit
}

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
