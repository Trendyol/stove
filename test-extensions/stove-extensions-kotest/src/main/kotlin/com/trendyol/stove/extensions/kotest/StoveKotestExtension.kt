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
   * Builds a display path by traversing the parent chain and prepending
   * each test case's prefix (if any) to its name.
   *
   * For BehaviourSpec: ["Given: valid request", "When: creating", "Then: should succeed"]
   * For FunSpec with context: ["context order creation", "should create order"]
   * For flat FunSpec: ["should create order"]
   */
  private fun TestCase.buildDisplayPath(): List<String> {
    val chain = mutableListOf<TestCase>()
    var current: TestCase? = this
    while (current != null) {
      chain.add(0, current)
      current = current.parent
    }
    return chain.map { tc ->
      val prefix = tc.name.prefix ?: ""
      "$prefix${tc.name.name}"
    }
  }

  private fun TestResult.enrichIfFailed(): TestResult {
    if (!Stove.options().shouldEnrichFailures()) return this

    return when (this) {
      is TestResult.Failure -> enrichFailure()
      is TestResult.Error -> enrichError()
      else -> this
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

  private fun TestResult.Failure.enrichFailure(): TestResult {
    val fullReport = TraceReportBuilder.buildFullReport()
    return if (fullReport.isNotEmpty()) {
      TestResult.Failure(
        duration,
        StoveTestFailureException(cause.toFailureMessage(), fullReport, cause)
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
        StoveTestErrorException(cause.toFailureMessage(), fullReport, cause)
      )
    } else {
      this
    }
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
    clear(ctx.testId)
  }
}
