package com.trendyol.stove.testing.e2e.reporting

import com.trendyol.stove.testing.e2e.system.TestSystem
import io.kotest.core.extensions.TestCaseExtension
import io.kotest.core.test.TestCase
import io.kotest.engine.test.TestResult

/**
 * Kotest extension that automatically manages test context and enriches test failures
 * with Stove's execution report.
 *
 * When a test fails, the report is included in the exception message so that Kotest's
 * test engine displays what happened during the test execution.
 *
 * Register this extension in your Kotest project config:
 * ```kotlin
 * class TestConfig : AbstractProjectConfig() {
 *     override fun extensions() = listOf(StoveKotestExtension())
 * }
 * ```
 */
class StoveKotestExtension : TestCaseExtension {
  override suspend fun intercept(
    testCase: TestCase,
    execute: suspend (TestCase) -> TestResult
  ): TestResult {
    if (!TestSystem.instanceInitialized()) {
      return execute(testCase)
    }

    val reporter = TestSystem.reporter()
    val options = TestSystem.instance.options

    return reporter.withTestContext(testCase.toStoveContext()) {
      execute(testCase).enrichIfFailed(options, reporter)
    }
  }

  private fun TestCase.toStoveContext() = StoveTestContext(
    testId = "${spec::class.simpleName}::${name.name}",
    testName = name.name,
    specName = spec::class.simpleName
  )

  private fun TestResult.enrichIfFailed(
    options: com.trendyol.stove.testing.e2e.system.TestSystemOptions,
    reporter: StoveReporter
  ): TestResult {
    if (!options.shouldEnrichFailures()) return this

    return when (this) {
      is TestResult.Failure -> enrichFailure(reporter, options)
      is TestResult.Error -> enrichError(reporter, options)
      else -> this
    }
  }

  private fun TestResult.Failure.enrichFailure(
    reporter: StoveReporter,
    options: com.trendyol.stove.testing.e2e.system.TestSystemOptions
  ): TestResult = reporter
    .dumpIfFailed(options.failureRenderer)
    .takeIf { it.isNotEmpty() }
    ?.let { report ->
      TestResult.Failure(
        duration,
        StoveTestFailureException(cause.message ?: "Test failed", report, cause)
      )
    } ?: this

  private fun TestResult.Error.enrichError(
    reporter: StoveReporter,
    options: com.trendyol.stove.testing.e2e.system.TestSystemOptions
  ): TestResult = reporter
    .dumpIfFailed(options.failureRenderer)
    .takeIf { it.isNotEmpty() }
    ?.let { report ->
      TestResult.Error(
        duration,
        StoveTestErrorException(cause.message ?: "Test error", report, cause)
      )
    } ?: this
}

private fun com.trendyol.stove.testing.e2e.system.TestSystemOptions.shouldEnrichFailures() =
  dumpReportOnTestFailure && reportingEnabled

/**
 * Executes the block within a test context, ensuring proper setup and cleanup.
 */
private inline fun <T> StoveReporter.withTestContext(
  ctx: StoveTestContext,
  block: () -> T
): T {
  startTest(ctx)
  return try {
    block()
  } finally {
    endTest()
    clear()
  }
}

/**
 * Exception that wraps test assertion failures with Stove's execution report.
 * The report is included in the exception message for display by test engines.
 *
 * Preserves the original exception's stack trace so test frameworks show the actual failure location.
 */
class StoveTestFailureException(
  originalMessage: String,
  stoveReport: String,
  cause: Throwable? = null
) : AssertionError(buildStoveReportMessage(originalMessage, stoveReport), cause) {
  init {
    // Copy the original stack trace to show the actual failure location
    cause?.let { stackTrace = it.stackTrace }
  }
}

/**
 * Exception that wraps test errors with Stove's execution report.
 * The report is included in the exception message for display by test engines.
 *
 * Preserves the original exception's stack trace so test frameworks show the actual failure location.
 */
class StoveTestErrorException(
  originalMessage: String,
  stoveReport: String,
  cause: Throwable? = null
) : Exception(buildStoveReportMessage(originalMessage, stoveReport), cause) {
  init {
    // Copy the original stack trace to show the actual failure location
    cause?.let { stackTrace = it.stackTrace }
  }
}

private fun buildStoveReportMessage(
  originalMessage: String,
  stoveReport: String
): String = """
  |$originalMessage
  |
  |═══════════════════════════════════════════════════════════════════════════════
  |                         STOVE EXECUTION REPORT
  |═══════════════════════════════════════════════════════════════════════════════
  |
  |$stoveReport
  """.trimMargin()
