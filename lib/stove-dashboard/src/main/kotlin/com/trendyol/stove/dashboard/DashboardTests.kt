package com.trendyol.stove.dashboard

import arrow.core.*
import com.trendyol.stove.dashboard.api.*
import com.trendyol.stove.reporting.ReportEntry
import com.trendyol.stove.reporting.StoveTestContext
import java.time.Duration
import java.time.Instant

/** Access is serialized by the dashboard's lifecycle lock. */
internal class DashboardTests(private val emit: DashboardEventEmitter) {
  private data class RunningTest(val start: Instant, val failure: Option<String> = None)
  private data class Results(
    val running: Map<String, RunningTest> = emptyMap(),
    val passed: Int = 0,
    val failed: Int = 0
  )

  private var results = Results()

  fun start(ctx: StoveTestContext, started: Instant) {
    if (ctx.testId in results.running) return
    results = results.copy(running = results.running + (ctx.testId to RunningTest(started)))
    emit {
      testStarted = TestStartedEvent.newBuilder().setTestId(ctx.testId)
        .setTestName(ctx.testName).setSpecName(ctx.specName.orEmpty())
        .setTimestamp(started.toTimestamp()).addAllTestPath(ctx.testPath).build()
    }
  }

  fun fail(testId: String, error: String) {
    results.running.getOrNone(testId).onSome { test ->
      results = results.copy(running = results.running + (testId to test.copy(failure = Some(error))))
    }
  }

  fun record(entry: ReportEntry) {
    if (entry.testId !in results.running) return
    emit { entryRecorded = entry.toProto() }
  }

  fun finish(testId: String, ended: Instant, interrupted: Boolean = false) {
    val (started, failure) = results.running.getOrNone(testId).getOrElse { return }
    val status = when {
      failure.isSome() -> TestOutcome.FAILED
      interrupted -> TestOutcome.ERROR
      else -> TestOutcome.PASSED
    }
    val error = failure.getOrElse { if (interrupted) "Test ended before the framework reported an outcome" else "" }
    emit {
      testEnded = TestEndedEvent.newBuilder().setTestId(testId).setStatus(status.name)
        .setDurationMs(Duration.between(started, ended).toMillis().coerceAtLeast(0))
        .setError(error).setTimestamp(ended.toTimestamp()).build()
    }
    val remaining = results.running - testId
    results = if (status == TestOutcome.PASSED) {
      results.copy(running = remaining, passed = results.passed + 1)
    } else {
      results.copy(running = remaining, failed = results.failed + 1)
    }
  }

  fun close() = results.running.keys.forEach { finish(it, Instant.now(), interrupted = true) }

  fun result(durationMs: Long, lifecycleFailed: Boolean): RunEndedEvent = RunEndedEvent.newBuilder()
    .setTimestamp(
      Instant.now().toTimestamp()
    ).setTotalTests(results.running.size + results.passed + results.failed).setPassed(results.passed)
    .setFailed(results.failed).setDurationMs(durationMs).setLifecycleFailed(lifecycleFailed).build()
}

private enum class TestOutcome { PASSED, FAILED, ERROR }
