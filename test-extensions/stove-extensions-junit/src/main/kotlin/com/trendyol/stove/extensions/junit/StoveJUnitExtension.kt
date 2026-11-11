package com.trendyol.stove.extensions.junit

import com.trendyol.stove.extensions.StoveTestSession
import com.trendyol.stove.reporting.StoveTestContext
import com.trendyol.stove.reporting.StoveTestFailureException
import com.trendyol.stove.system.Stove
import com.trendyol.stove.tracing.TraceContext
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
  private val namespace = ExtensionContext.Namespace.create(StoveJUnitExtension::class.java)
  private val sessionKey = "stove.session"

  override fun beforeEach(context: ExtensionContext) {
    if (!Stove.instanceInitialized()) return

    val ctx = context.toStoveContext()
    context.getStore(namespace).put(sessionKey, StoveTestSession.open(ctx))
  }

  override fun handleTestExecutionException(context: ExtensionContext, throwable: Throwable) {
    if (!Stove.instanceInitialized()) throw throwable

    val message = StoveTestSession.failureMessage(throwable)
    StoveTestSession.reportFailure(message)

    val fullReport = StoveTestSession.failureReport()
    if (fullReport.isNotEmpty()) {
      throw StoveTestFailureException(
        originalMessage = message,
        stoveReport = fullReport,
        cause = throwable
      )
    }

    throw throwable
  }

  override fun afterEach(context: ExtensionContext) {
    if (!Stove.instanceInitialized()) return

    context.getStore(namespace).remove(sessionKey, StoveTestSession::class.java)?.close()
  }

  private fun ExtensionContext.toStoveContext(): StoveTestContext {
    val hierarchy = generateSequence(this) { it.parent.orElse(null) }.toList().asReversed()
    val path = hierarchy.filter { it.isTestPathSegment() }.map { it.displayName }
    val fullName = path.joinToString(" / ")
    val rootClass = hierarchy.firstOrNull { it.testClass.isPresent }?.requiredTestClass?.simpleName
      ?: requiredTestClass.simpleName
    return StoveTestContext(
      // Display names may repeat across template invocations and nested classes.
      testId = TraceContext.sanitizeToAscii("$rootClass::$fullName::$uniqueId"),
      testName = displayName,
      specName = rootClass,
      testPath = path
    )
  }

  /** Includes test methods and nested classes, while leaving the root class in the spec name. */
  private fun ExtensionContext.isTestPathSegment(): Boolean =
    testMethod.isPresent || (testClass.isPresent && parent.flatMap { it.testClass }.isPresent)
}
