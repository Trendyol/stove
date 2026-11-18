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
  private val running = mutableMapOf<String, RunningTest>()
  private val completed = mutableMapOf<String, TestOutcome>()

  fun start(ctx: StoveTestContext, started: Instant) {
    if (ctx.testId in running) return
    running[ctx.testId] = RunningTest(started)
    emit {
      testStarted = TestStartedEvent.newBuilder().setTestId(ctx.testId)
        .setTestName(ctx.testName).setSpecName(ctx.specName.orEmpty())
        .setTimestamp(started.toTimestamp()).addAllTestPath(ctx.testPath).build()
    }
  }

  fun fail(testId: String, error: String) {
    running.getOrNone(testId).onSome { test ->
      running[testId] = test.copy(failure = Some(error))
    }
  }

  fun record(entry: ReportEntry) {
    if (entry.testId !in running) return
    emit { entryRecorded = entry.toProto() }
  }

  fun finish(testId: String, ended: Instant, interrupted: Boolean = false) {
    val (started, failure) = running.getOrNone(testId).getOrElse { return }
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
    running.remove(testId)
    completed[testId] = status
  }

  fun close(ended: Instant) = running.keys.toList().forEach { finish(it, ended, interrupted = true) }

  fun result(ended: Instant, durationMs: Long, lifecycleFailed: Boolean): RunEndedEvent {
    val passed = completed.values.count { it == TestOutcome.PASSED }
    return RunEndedEvent.newBuilder().setTimestamp(ended.toTimestamp()).setTotalTests(completed.size)
      .setPassed(passed).setFailed(completed.size - passed).setDurationMs(durationMs).setLifecycleFailed(lifecycleFailed).build()
  }
}

private enum class TestOutcome { PASSED, FAILED, ERROR }
