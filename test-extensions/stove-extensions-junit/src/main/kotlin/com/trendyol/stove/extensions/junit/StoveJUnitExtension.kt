package com.trendyol.stove.extensions.junit

import com.trendyol.stove.reporting.StoveTestContext
import com.trendyol.stove.reporting.StoveTestContextHolder
import com.trendyol.stove.reporting.StoveTestFailureException
import com.trendyol.stove.system.Stove
import com.trendyol.stove.tracing.TraceContext
import com.trendyol.stove.tracing.TraceReportBuilder
import com.trendyol.stove.tracing.TraceReportBuilder.shouldEnrichFailures
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler

/**
 * JUnit extension that automatically manages test context and enriches test failures
 * with Stove's execution report.
 *
 * When a test fails, the report is included in the exception message so that JUnit's
 * test engine displays what happened during the test execution.
 *
 * This extension works with both JUnit 5 and JUnit 6, as both use the JUnit Jupiter API.
 *
 * Register this extension on your test class:
 * ```kotlin
 * @ExtendWith(StoveJUnitExtension::class)
 * class MyE2ETests { ... }
 * ```
 *
 * Or globally via @RegisterExtension:
 * ```kotlin
 * companion object {
 *     @JvmField
 *     @RegisterExtension
 *     val stove = StoveJUnitExtension()
 * }
 * ```
 */
class StoveJUnitExtension :
  BeforeEachCallback,
  AfterEachCallback,
  TestExecutionExceptionHandler {
  override fun beforeEach(context: ExtensionContext) {
    if (!Stove.instanceInitialized()) return

    val ctx = context.toStoveContext()
    StoveTestContextHolder.set(ctx)
    Stove.reporter().startTest(ctx)
    TraceContext.start(ctx.testId)
  }

  override fun handleTestExecutionException(context: ExtensionContext, throwable: Throwable) {
    if (!Stove.instanceInitialized()) throw throwable

    val options = Stove.options()
    if (!options.shouldEnrichFailures()) throw throwable

    val fullReport = TraceReportBuilder.buildFullReport()
    if (fullReport.isNotEmpty()) {
      throw StoveTestFailureException(
        originalMessage = throwable.message ?: TraceReportBuilder.DEFAULT_ERROR_MESSAGE,
        stoveReport = fullReport,
        cause = throwable
      )
    }

    throw throwable
  }

  override fun afterEach(context: ExtensionContext) {
    if (!Stove.instanceInitialized()) return

    TraceContext.clear()
    Stove.reporter().run {
      endTest()
      clear()
    }
    StoveTestContextHolder.clear()
  }

  private fun ExtensionContext.toStoveContext() = StoveTestContext(
    testId = "${requiredTestClass.simpleName}::${requiredTestMethod.name}",
    testName = displayName,
    specName = requiredTestClass.simpleName
  )
}
