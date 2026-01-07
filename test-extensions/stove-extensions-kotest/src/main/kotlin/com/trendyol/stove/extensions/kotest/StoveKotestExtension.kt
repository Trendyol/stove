package com.trendyol.stove.extensions.kotest

import com.trendyol.stove.reporting.*
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.StoveOptions
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
    if (!Stove.instanceInitialized()) {
      return execute(testCase)
    }

    val reporter = Stove.reporter()
    val options = Stove.options()

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
    options: StoveOptions,
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
    options: StoveOptions
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
    options: StoveOptions
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

private fun StoveOptions.shouldEnrichFailures() =
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
