@file:Suppress("TooGenericExceptionCaught")

package com.trendyol.stove.reporting

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.toOption
import com.trendyol.stove.system.abstractions.PluggedSystem

/**
 * Interface for systems that participate in test reporting.
 *
 * Provides recording capabilities for actions during test execution.
 * Every action has an implicit or explicit result (PASSED/FAILED).
 *
 * ## Design Principles
 * - **Functional**: Uses Option monad, immutable data, no nullability
 * - **Simple**: Single entry type for all operations
 * - **Composable**: `record` combines action recording with execution
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
    get() = (this as? PluggedSystem)?.stove?.reporter
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
   * Execute an action and report the result.
   *
   * This is the preferred method for actions that include assertions.
   * It handles success/failure reporting automatically and re-throws on failure.
   *
   * @param action Description of the action being performed
   * @param input Optional input data for the action
   * @param output Optional output data (if not provided, block result is used)
   * @param metadata Additional metadata for the report entry
   * @param expected Optional expected result description
   * @param actual Optional actual result description
   * @param block The block to execute
   *
   * Example:
   * ```kotlin
   * report(
   *   action = "GET /users/123",
   *   input = Some(queryParams),
   *   expected = Some("200 OK")
   * ) {
   *   response.status shouldBe 200
   * }
   * ```
   */
  suspend fun <T> report(
    action: String,
    input: Option<Any> = None,
    output: Option<Any> = None,
    metadata: Map<String, Any> = emptyMap(),
    expected: Option<Any> = None,
    actual: Option<Any> = None,
    block: suspend () -> T
  ): T {
    if (!reporter.isEnabled) return block()

    return try {
      val result = block()
      val finalOutput = output.fold({ result.toOption() }, { Some(it) })
      reporter.record(
        ReportEntry.action(
          system = reportSystemName,
          testId = reporter.currentTestId(),
          action = action,
          passed = true,
          input = input,
          output = finalOutput,
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
