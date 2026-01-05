@file:Suppress("TooGenericExceptionCaught")

package com.trendyol.stove.testing.e2e.reporting

import com.trendyol.stove.testing.e2e.system.abstractions.PluggedSystem

/**
 * Interface for systems that participate in test reporting.
 *
 * Provides recording capabilities for actions and assertions during test execution,
 * following the Single Responsibility Principle - this interface only handles reporting.
 *
 * ## Design Principles
 * - **Functional**: Recording functions return unit, using callbacks for results
 * - **Immutable**: All recorded entries are immutable data classes
 * - **Composable**: `recordAndExecute` combines action recording with assertion execution
 */
interface Reports {
  /**
   * System identifier for reports. Defaults to class name without "System" suffix.
   * Override only when custom naming is needed (e.g., "HTTP" instead of "Http").
   */
  val reportSystemName: String
    get() = this::class.simpleName?.removeSuffix("System") ?: "Unknown"

  /**
   * Access to the reporter. Requires implementing class to be a [PluggedSystem].
   */
  val reporter: StoveReporter
    get() = (this as? PluggedSystem)?.testSystem?.reporter
      ?: error("Reports must be implemented by a PluggedSystem")

  /**
   * Capture current system state for failure reports.
   * Override to provide system-specific snapshots (e.g., Kafka messages, WireMock stubs).
   */
  fun snapshot(): SystemSnapshot = SystemSnapshot(
    system = reportSystemName,
    state = emptyMap(),
    summary = "No detailed state available"
  )

  /**
   * Record an action without assertion.
   *
   * @param action Description of the action performed
   * @param input Optional input data (request body, query params, etc.)
   * @param output Optional output data (response body, result, etc.)
   * @param metadata Additional key-value metadata
   */
  fun recordAction(
    action: String,
    input: Any? = null,
    output: Any? = null,
    metadata: Map<String, Any> = emptyMap()
  ) {
    if (!reporter.isEnabled) return
    reporter.record(
      ReportEntryFactory.action(
        system = reportSystemName,
        testId = reporter.currentTestId(),
        action = action,
        input = input,
        output = output,
        metadata = metadata
      )
    )
  }

  /**
   * Execute an assertion and record the combined action with result.
   *
   * This is the preferred method for actions that include assertions.
   * It handles success/failure recording automatically and re-throws on failure.
   *
   * Example:
   * ```kotlin
   * recordAndExecute(
   *   action = "GET /users/123",
   *   input = queryParams,
   *   expected = "200 OK"
   * ) {
   *   response.status shouldBe 200
   * }
   * ```
   */
  suspend fun <T> recordAndExecute(
    action: String,
    input: Any? = null,
    output: Any? = null,
    metadata: Map<String, Any> = emptyMap(),
    expected: Any? = null,
    actual: Any? = null,
    assertion: suspend () -> T
  ): T = executeWithRecording(action, input, output, metadata, expected, actual, assertion)

  /**
   * Alias for [recordAndExecute] - maintained for API compatibility.
   */
  suspend fun <T> recordAndExecuteSuspend(
    action: String,
    input: Any? = null,
    output: Any? = null,
    metadata: Map<String, Any> = emptyMap(),
    expected: Any? = null,
    actual: Any? = null,
    assertion: suspend () -> T
  ): T = recordAndExecute(action, input, output, metadata, expected, actual, assertion)

  /**
   * Record a standalone assertion result.
   * Use when you need to record an assertion separate from an action.
   */
  fun recordAssertion(
    description: String,
    expected: Any? = null,
    actual: Any? = null,
    passed: Boolean,
    failure: Throwable? = null,
    metadata: Map<String, Any> = emptyMap()
  ) {
    if (!reporter.isEnabled) return

    reporter.record(
      ReportEntryFactory.assertion(
        system = reportSystemName,
        testId = reporter.currentTestId(),
        description = description,
        expected = expected,
        actual = actual,
        passed = passed,
        failure = failure
      )
    )
  }

  // Private implementation - single place for recording logic
  private suspend fun <T> executeWithRecording(
    action: String,
    input: Any?,
    output: Any?,
    metadata: Map<String, Any>,
    expected: Any?,
    actual: Any?,
    assertion: suspend () -> T
  ): T {
    if (!reporter.isEnabled) return assertion()

    return try {
      val result = assertion()
      recordActionResult(action, input, output, metadata, expected, actual, true, null)
      result
    } catch (e: Throwable) {
      recordActionResult(action, input, output, metadata, expected, actual, false, e)
      throw e
    }
  }

  private fun recordActionResult(
    action: String,
    input: Any?,
    output: Any?,
    metadata: Map<String, Any>,
    expected: Any?,
    actual: Any?,
    passed: Boolean,
    error: Throwable?
  ) {
    reporter.record(
      ReportEntryFactory.actionWithResult(
        system = reportSystemName,
        testId = reporter.currentTestId(),
        action = action,
        input = input,
        output = output,
        metadata = metadata,
        passed = passed,
        expected = expected,
        actual = actual,
        error = error?.message
      )
    )
  }
}
