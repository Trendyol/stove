package com.trendyol.stove.testing.e2e.reporting

import java.time.Instant

/**
 * Base sealed class for all report entries.
 * Immutable by design - all properties are val and data classes ensure proper equals/hashCode.
 */
sealed class ReportEntry {
  abstract val timestamp: Instant
  abstract val system: String
  abstract val testId: String

  /** Display summary for this entry */
  abstract val summary: String

  /** True if this entry represents a failure */
  val isFailed: Boolean get() = result == AssertionResult.FAILED

  /** True if this entry represents a success */
  val isPassed: Boolean get() = result == AssertionResult.PASSED

  /** Optional result - null means action only without assertion */
  abstract val result: AssertionResult?
}

/**
 * Represents an action performed by a system (HTTP request, Kafka publish, SQL query, etc.).
 * May optionally include an assertion result.
 */
data class ActionEntry(
  override val timestamp: Instant,
  override val system: String,
  override val testId: String,
  val action: String,
  val input: Any? = null,
  val output: Any? = null,
  val metadata: Map<String, Any> = emptyMap(),
  override val result: AssertionResult? = null,
  val expected: Any? = null,
  val actual: Any? = null,
  val error: String? = null
) : ReportEntry() {
  override val summary: String get() = "[$system] $action"
}

/**
 * Represents a standalone assertion during test execution.
 */
data class AssertionEntry(
  override val timestamp: Instant,
  override val system: String,
  override val testId: String,
  val description: String,
  val expected: Any? = null,
  val actual: Any? = null,
  override val result: AssertionResult,
  val failure: Throwable? = null
) : ReportEntry() {
  override val summary: String get() = "[$system] $description: ${result.name}"
}

/**
 * Result of an assertion.
 */
enum class AssertionResult {
  PASSED,
  FAILED;

  companion object {
    fun of(passed: Boolean): AssertionResult = if (passed) PASSED else FAILED
  }
}

/**
 * Factory for creating report entries with consistent timestamping.
 */
object ReportEntryFactory {
  private fun now(): Instant = Instant.now()

  fun action(
    system: String,
    testId: String,
    action: String,
    input: Any? = null,
    output: Any? = null,
    metadata: Map<String, Any> = emptyMap()
  ): ActionEntry = ActionEntry(
    timestamp = now(),
    system = system,
    testId = testId,
    action = action,
    input = input,
    output = output,
    metadata = metadata
  )

  fun actionWithResult(
    system: String,
    testId: String,
    action: String,
    passed: Boolean,
    input: Any? = null,
    output: Any? = null,
    metadata: Map<String, Any> = emptyMap(),
    expected: Any? = null,
    actual: Any? = null,
    error: String? = null
  ): ActionEntry = ActionEntry(
    timestamp = now(),
    system = system,
    testId = testId,
    action = action,
    input = input,
    output = output,
    metadata = metadata,
    result = AssertionResult.of(passed),
    expected = expected,
    actual = actual,
    error = error
  )

  fun assertion(
    system: String,
    testId: String,
    description: String,
    passed: Boolean,
    expected: Any? = null,
    actual: Any? = null,
    failure: Throwable? = null
  ): AssertionEntry = AssertionEntry(
    timestamp = now(),
    system = system,
    testId = testId,
    description = description,
    expected = expected,
    actual = actual,
    result = AssertionResult.of(passed),
    failure = failure
  )
}
