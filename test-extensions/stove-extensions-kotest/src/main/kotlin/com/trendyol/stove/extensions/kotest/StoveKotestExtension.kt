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
import io.kotest.core.test.TestType
import io.kotest.engine.test.TestResult
import kotlinx.coroutines.withContext

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

    // Only wrap leaf tests in test context — containers (context/given/when/describe blocks)
    // should pass through without starting/ending a test report.
    if (testCase.type != TestType.Test) {
      return execute(testCase)
    }

    return Stove.reporter().withTestContext(testCase.toStoveContext()) {
      execute(testCase)
        .reportFailureIfNeeded()
        .enrichIfFailed()
    }
  }

  private fun TestCase.toStoveContext(): StoveTestContext {
    val path = buildDisplayPath()
    val fullName = path.joinToString(" / ")
    return StoveTestContext(
      testId = TraceContext.sanitizeToAscii("${spec::class.simpleName}::$fullName"),
      testName = name.name,
      specName = spec::class.simpleName,
      testPath = path
    )
  }

  /**
   * Builds the path from the outermost context to the leaf test, including Kotest display prefixes.
   */
  private fun TestCase.buildDisplayPath(): List<String> =
    generateSequence(this) { it.parent }
      .toList()
      .asReversed()
      .map { test -> "${test.name.prefix.orEmpty()}${test.name.name}" }

  private fun TestResult.enrichIfFailed(): TestResult {
    if (!Stove.options().shouldEnrichFailures()) return this
    if (this !is TestResult.Failure && this !is TestResult.Error) return this

    val report = TraceReportBuilder.buildFullReport()
    if (report.isEmpty()) return this

    return when (this) {
      is TestResult.Failure -> TestResult.Failure(
        duration,
        StoveTestFailureException(cause.toFailureMessage(), report, cause)
      )

      is TestResult.Error -> TestResult.Error(
        duration,
        StoveTestErrorException(cause.toFailureMessage(), report, cause)
      )
    }
  }

  private fun TestResult.reportFailureIfNeeded(): TestResult {
    when (this) {
      is TestResult.Failure -> Stove.reporter().reportFailure(cause.toFailureMessage())
      is TestResult.Error -> Stove.reporter().reportFailure(cause.toFailureMessage())
      else -> Unit
    }
    return this
  }

  private fun Throwable.toFailureMessage(): String =
    message ?: this::class.simpleName ?: TraceReportBuilder.DEFAULT_ERROR_MESSAGE
}

/**
 * Executes the block within a test context, ensuring proper setup and cleanup.
 * Also starts/ends tracing automatically for the test.
 */
private suspend fun <T> StoveReporter.withTestContext(
  ctx: StoveTestContext,
  block: suspend () -> T
): T = withContext(ctx) {
  // Installing [ctx] as a coroutine context element mirrors it into StoveTestContextHolder
  // across dispatcher thread switches, so report entries recorded from coroutines that
  // resumed on another worker thread still resolve the correct test id.
  startTest(ctx)
  TraceContext.start(ctx.testId)
  try {
    TraceContext.withCurrentPropagation {
      block()
    }
  } finally {
    TraceContext.clear()
    endTest()
    clear(ctx.testId)
  }
}
