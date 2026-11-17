package com.trendyol.stove.dashboard

import arrow.core.*
import com.trendyol.stove.dashboard.api.*
import com.trendyol.stove.reporting.ReportEntry
import com.trendyol.stove.reporting.StoveTestContext
import java.time.Duration
import java.time.Instant

/** Access is serialized by the dashboard's lifecycle lock. */
internal class DashboardTests(private val emit: DashboardEventEmitter) {
  private val starts = mutableMapOf<String, TestStart>()
  private val failures = mutableMapOf<String, String>()
  private var total = 0
  private var passed = 0
  private var failed = 0

  fun start(ctx: StoveTestContext) {
    if (ctx.testId in starts) return
    starts[ctx.testId] = TestStart(ctx, Instant.now())
    total++
    emit {
      testStarted = TestStartedEvent.newBuilder().setTestId(ctx.testId)
        .setTestName(ctx.testName).setSpecName(ctx.specName ?: "")
        .setTimestamp(Instant.now().toTimestamp()).addAllTestPath(ctx.testPath).build()
    }
  }

  fun fail(testId: String, error: String) {
    if (testId in starts) failures[testId] = error
  }

  fun record(entry: ReportEntry) {
    if (entry.testId !in starts) return
    emit { entryRecorded = entry.toProto() }
  }

  fun finish(testId: String, interrupted: Boolean = false) {
    val started = starts.remove(testId).toOption().getOrElse { return }
    val failure = failures.remove(testId).toOption()
    val status = when {
      failure.isSome() -> TestOutcome.FAILED
      interrupted -> TestOutcome.ERROR
      else -> TestOutcome.PASSED
    }
    val error = failure.getOrElse { if (interrupted) "Test ended before the framework reported an outcome" else "" }
    emit {
      testEnded = TestEndedEvent.newBuilder().setTestId(testId).setStatus(status.name)
        .setDurationMs(Duration.between(started.at, Instant.now()).toMillis())
        .setError(error).setTimestamp(Instant.now().toTimestamp()).build()
    }
    if (status == TestOutcome.PASSED) passed++ else failed++
  }

  fun close() = starts.keys.toList().forEach { finish(it, interrupted = true) }

  fun result(durationMs: Long, lifecycleFailed: Boolean): RunEndedEvent = RunEndedEvent.newBuilder()
    .setTimestamp(Instant.now().toTimestamp()).setTotalTests(total).setPassed(passed)
    .setFailed(failed).setDurationMs(durationMs).setLifecycleFailed(lifecycleFailed).build()
}

private data class TestStart(val context: StoveTestContext, val at: Instant)

private enum class TestOutcome { PASSED, FAILED, ERROR }
