package com.trendyol.stove.extensions.kotest

import com.trendyol.stove.reporting.StoveReporter
import com.trendyol.stove.reporting.StoveTestContext
import com.trendyol.stove.reporting.StoveTestErrorException
import com.trendyol.stove.reporting.StoveTestFailureException
import com.trendyol.stove.system.Stove
import com.trendyol.stove.tracing.TraceContext
import com.trendyol.stove.tracing.TraceReportBuilder
import com.trendyol.stove.tracing.TraceReportBuilder.shouldEnrichFailures
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

    return Stove.reporter().withTestContext(testCase.toStoveContext()) {
      execute(testCase).enrichIfFailed()
    }
  }

  private fun TestCase.toStoveContext() = StoveTestContext(
    testId = TraceContext.sanitizeToAscii("${spec::class.simpleName}::${name.name}"),
    testName = name.name,
    specName = spec::class.simpleName
  )

  private fun TestResult.enrichIfFailed(): TestResult {
    if (!Stove.options().shouldEnrichFailures()) return this

    return when (this) {
      is TestResult.Failure -> enrichFailure()
      is TestResult.Error -> enrichError()
      else -> this
    }
  }

  private fun TestResult.Failure.enrichFailure(): TestResult {
    val fullReport = TraceReportBuilder.buildFullReport()
    return if (fullReport.isNotEmpty()) {
      TestResult.Failure(
        duration,
        StoveTestFailureException(cause.message ?: TraceReportBuilder.DEFAULT_ERROR_MESSAGE, fullReport, cause)
      )
    } else {
      this
    }
  }

  private fun TestResult.Error.enrichError(): TestResult {
    val fullReport = TraceReportBuilder.buildFullReport()
    return if (fullReport.isNotEmpty()) {
      TestResult.Error(
        duration,
        StoveTestErrorException(cause.message ?: TraceReportBuilder.DEFAULT_ERROR_MESSAGE, fullReport, cause)
      )
    } else {
      this
    }
  }
}

/**
 * Executes the block within a test context, ensuring proper setup and cleanup.
 * Also starts/ends tracing automatically for the test.
 */
private suspend fun <T> StoveReporter.withTestContext(
  ctx: StoveTestContext,
  block: suspend () -> T
): T {
  startTest(ctx)
  TraceContext.start(ctx.testId)
  return try {
    TraceContext.withCurrentPropagation {
      block()
    }
  } finally {
    TraceContext.clear()
    endTest()
    clear()
  }
}
