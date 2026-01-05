package com.trendyol.stove.testing.e2e.reporting

import com.trendyol.stove.testing.e2e.system.TestSystem
import org.junit.jupiter.api.extension.*

/**
 * JUnit 5 extension that automatically manages test context and enriches test failures
 * with Stove's execution report.
 *
 * When a test fails, the report is included in the exception message so that JUnit's
 * test engine displays what happened during the test execution.
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
    if (!TestSystem.instanceInitialized()) return

    val ctx = StoveTestContext(
      testId = "${context.requiredTestClass.simpleName}::${context.requiredTestMethod.name}",
      testName = context.displayName,
      specName = context.requiredTestClass.simpleName
    )

    StoveTestContextHolder.set(ctx)
    TestSystem.reporter().startTest(ctx)
  }

  override fun handleTestExecutionException(context: ExtensionContext, throwable: Throwable) {
    if (!TestSystem.instanceInitialized()) {
      throw throwable
    }

    val reporter = TestSystem.reporter()
    val options = TestSystem.instance.options

    // Enrich the exception with Stove report if enabled
    if (options.dumpReportOnTestFailure && options.reportingEnabled) {
      val report = reporter.dumpIfFailed(options.failureRenderer)
      if (report.isNotEmpty()) {
        throw StoveTestFailureException(
          originalMessage = throwable.message ?: "Test failed",
          stoveReport = report,
          cause = throwable
        )
      }
    }

    // Re-throw original exception if no report or reporting disabled
    throw throwable
  }

  override fun afterEach(context: ExtensionContext) {
    if (!TestSystem.instanceInitialized()) return

    val reporter = TestSystem.reporter()

    // Clear report for next test
    reporter.endTest()
    reporter.clear()
    StoveTestContextHolder.clear()
  }
}
