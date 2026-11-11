package com.trendyol.stove.extensions.kotest

import com.trendyol.stove.extensions.StoveTestSession
import com.trendyol.stove.reporting.StoveTestContext
import com.trendyol.stove.reporting.StoveTestErrorException
import com.trendyol.stove.reporting.StoveTestFailureException
import com.trendyol.stove.system.Stove
import com.trendyol.stove.tracing.TraceContext
import io.kotest.core.extensions.TestCaseExtension
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestType
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

    // Only wrap leaf tests in test context — containers (context/given/when/describe blocks)
    // should pass through without starting/ending a test report.
    if (testCase.type != TestType.Test) {
      return execute(testCase)
    }

    return StoveTestSession.run(testCase.toStoveContext()) {
      execute(testCase).withStoveFailureReport()
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

  private fun TestResult.withStoveFailureReport(): TestResult {
    val cause = when (this) {
      is TestResult.Failure -> cause
      is TestResult.Error -> cause
      else -> return this
    }
    val message = StoveTestSession.failureMessage(cause)
    StoveTestSession.reportFailure(message)
    val report = StoveTestSession.failureReport()
    if (report.isEmpty()) return this

    return when (this) {
      is TestResult.Failure -> TestResult.Failure(duration, StoveTestFailureException(message, report, cause))
      is TestResult.Error -> TestResult.Error(duration, StoveTestErrorException(message, report, cause))
    }
  }
}
