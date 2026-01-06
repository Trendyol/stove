@file:Suppress("TooGenericExceptionCaught")

package com.trendyol.stove.testing.e2e.reporting

import arrow.core.None
import arrow.core.Option
import arrow.core.toOption
import com.trendyol.stove.testing.e2e.system.abstractions.PluggedSystem

/**
 * Interface for systems that participate in test reporting.
 *
 * Provides recording capabilities for actions during test execution.
 * Every action has an implicit or explicit result (PASSED/FAILED).
 *
 * ## Design Principles
 * - **Functional**: Uses Option monad, immutable data, no nullability
 * - **Simple**: Single entry type for all operations
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
   * Record a successful action (action completed without throwing).
   */
  fun recordSuccess(
    action: String,
    input: Option<Any> = None,
    output: Option<Any> = None,
    metadata: Map<String, Any> = emptyMap()
  ) {
    if (!reporter.isEnabled) return
    reporter.record(
      ReportEntry.success(
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
   * Execute an action and record the result.
   *
   * This is the preferred method for actions that include assertions.
   * It handles success/failure recording automatically and re-throws on failure.
   *
   * Example:
   * ```kotlin
   * recordAndExecute(
   *   action = "GET /users/123",
   *   input = Some(queryParams),
   *   expected = Some("200 OK")
   * ) {
   *   response.status shouldBe 200
   * }
   * ```
   */
  suspend fun <T> recordAndExecute(
    action: String,
    input: Option<Any> = None,
    output: Option<Any> = None,
    metadata: Map<String, Any> = emptyMap(),
    expected: Option<Any> = None,
    actual: Option<Any> = None,
    assertion: suspend () -> T
  ): T {
    if (!reporter.isEnabled) return assertion()

    return try {
      val result = assertion()
      reporter.record(
        ReportEntry.action(
          system = reportSystemName,
          testId = reporter.currentTestId(),
          action = action,
          passed = true,
          input = input,
          output = output,
          metadata = metadata,
          expected = expected,
          actual = actual
        )
      )
      result
    } catch (e: Throwable) {
      reporter.record(
        ReportEntry.action(
          system = reportSystemName,
          testId = reporter.currentTestId(),
          action = action,
          passed = false,
          input = input,
          output = output,
          metadata = metadata,
          expected = expected,
          actual = actual,
          error = e.message.toOption()
        )
      )
      throw e
    }
  }
}
