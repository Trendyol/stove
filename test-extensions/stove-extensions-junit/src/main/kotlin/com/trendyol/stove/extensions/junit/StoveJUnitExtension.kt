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

    Stove.reporter().reportFailure(throwable.message ?: throwable::class.simpleName ?: "Test failed")

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

  private fun ExtensionContext.toStoveContext(): StoveTestContext {
    val path = buildTestPath()
    val fullName = path.joinToString(" / ")
    val rootClass = findRootTestClass()
    return StoveTestContext(
      testId = TraceContext.sanitizeToAscii("$rootClass::$fullName"),
      testName = displayName,
      specName = rootClass,
      testPath = path
    )
  }

  /**
   * Builds a test path by traversing the [ExtensionContext] parent chain.
   * For `@Nested` classes, each nested class display name becomes a path segment.
   * For flat tests, the path contains just the test method display name.
   */
  private fun ExtensionContext.buildTestPath(): List<String> {
    val segments = mutableListOf<String>()
    var ctx: ExtensionContext? = this
    while (ctx != null) {
      when {
        // Test method — always include
        ctx.testMethod.isPresent -> segments.add(0, ctx.displayName)

        // @Nested class — include if its parent also has a test class (meaning it's nested, not root)
        ctx.testClass.isPresent && ctx.parent.flatMap { it.testClass }.isPresent ->
          segments.add(0, ctx.displayName)
      }
      ctx = ctx.parent.orElse(null)
    }
    return segments
  }

  /**
   * Finds the outermost (root) test class name by traversing the parent chain.
   */
  private fun ExtensionContext.findRootTestClass(): String {
    var rootClass = requiredTestClass.simpleName
    var ctx: ExtensionContext? = parent.orElse(null)
    while (ctx != null) {
      if (ctx.testClass.isPresent) {
        rootClass = ctx.requiredTestClass.simpleName
      }
      ctx = ctx.parent.orElse(null)
    }
    return rootClass
  }
}
