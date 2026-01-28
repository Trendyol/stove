package com.trendyol.stove.reporting

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import com.trendyol.stove.tracing.TraceVisualization
import java.time.Instant

/**
 * Represents an action performed during test execution with its result.
 *
 * Every test operation is an action with an outcome:
 * - If it completes successfully → PASSED
 * - If it throws or fails assertion → FAILED
 *
 * Uses Arrow's Option monad for optional fields - no nullability.
 */
data class ReportEntry(
  val timestamp: Instant,
  val system: String,
  val testId: String,
  val action: String,
  val result: AssertionResult = AssertionResult.PASSED,
  val input: Option<Any> = None,
  val output: Option<Any> = None,
  val metadata: Map<String, Any> = emptyMap(),
  val expected: Option<Any> = None,
  val actual: Option<Any> = None,
  val error: Option<String> = None,
  val traceId: Option<String> = None,
  val executionTrace: Option<TraceVisualization> = None
) {
  val summary: String get() = "[$system] $action"
  val isFailed: Boolean get() = result == AssertionResult.FAILED
  val isPassed: Boolean get() = result == AssertionResult.PASSED
  val hasTrace: Boolean get() = traceId.isSome()

  companion object {
    private fun now(): Instant = Instant.now()

    /**
     * Creates a successful action entry.
     */
    fun success(
      system: String,
      testId: String,
      action: String,
      input: Option<Any> = None,
      output: Option<Any> = None,
      metadata: Map<String, Any> = emptyMap()
    ): ReportEntry = ReportEntry(
      timestamp = now(),
      system = system,
      testId = testId,
      action = action,
      result = AssertionResult.PASSED,
      input = input,
      output = output,
      metadata = metadata
    )

    /**
     * Creates an action entry with explicit result.
     */
    fun action(
      system: String,
      testId: String,
      action: String,
      passed: Boolean,
      input: Option<Any> = None,
      output: Option<Any> = None,
      metadata: Map<String, Any> = emptyMap(),
      expected: Option<Any> = None,
      actual: Option<Any> = None,
      error: Option<String> = None,
      traceId: Option<String> = None,
      executionTrace: Option<TraceVisualization> = None
    ): ReportEntry = ReportEntry(
      timestamp = now(),
      system = system,
      testId = testId,
      action = action,
      result = AssertionResult.of(passed),
      input = input,
      output = output,
      metadata = metadata,
      expected = expected,
      actual = actual,
      error = error,
      traceId = traceId,
      executionTrace = executionTrace
    )

    /**
     * Creates a failed action entry.
     */
    fun failure(
      system: String,
      testId: String,
      action: String,
      error: String,
      input: Option<Any> = None,
      output: Option<Any> = None,
      metadata: Map<String, Any> = emptyMap(),
      expected: Option<Any> = None,
      actual: Option<Any> = None
    ): ReportEntry = ReportEntry(
      timestamp = now(),
      system = system,
      testId = testId,
      action = action,
      result = AssertionResult.FAILED,
      input = input,
      output = output,
      metadata = metadata,
      expected = expected,
      actual = actual,
      error = Some(error)
    )
  }
}

/**
 * Result of an action/assertion.
 */
enum class AssertionResult {
  PASSED,
  FAILED;

  companion object {
    fun of(passed: Boolean): AssertionResult = if (passed) PASSED else FAILED
  }
}
