package com.trendyol.stove.reporting

import com.trendyol.stove.system.Stove
import java.util.concurrent.ConcurrentHashMap

/**
 * Central reporter that manages test reports and context.
 * Thread-safe for concurrent test execution.
 *
 * ## Design
 * - Each test gets its own [TestReport] container
 * - Test context is resolved from [StoveTestContextHolder] (ThreadLocal) or internal context
 * - Snapshots are collected from all systems implementing [Reports]
 */
class StoveReporter(
  val isEnabled: Boolean = true
) {
  private val reports = ConcurrentHashMap<String, TestReport>()
  private val contextThreadLocal = ThreadLocal<String>()

  /** Start tracking a new test */
  fun startTest(ctx: StoveTestContext) {
    contextThreadLocal.set(ctx.testId)
    reports.computeIfAbsent(ctx.testId) { TestReport(ctx.testId, ctx.testName) }
  }

  /** End tracking the current test */
  fun endTest(): Unit = contextThreadLocal.remove()

  /** Record an entry in the current test's report */
  fun record(entry: ReportEntry) {
    if (!isEnabled) return
    currentTest().record(entry)
  }

  /** Get report for current test, creating if needed */
  fun currentTest(): TestReport =
    reports.computeIfAbsent(currentTestId()) { TestReport(it, it) }

  /** Get report for current test if it exists */
  fun currentTestOrNull(): TestReport? =
    resolveTestId()?.let { reports[it] }

  /** Get current test ID */
  fun currentTestId(): String =
    resolveTestId() ?: DEFAULT_TEST_ID

  /** Check if current test has failures */
  fun hasFailures(): Boolean =
    currentTestOrNull()?.hasFailures() == true

  /** Clear current test report */
  fun clear(): Unit = currentTestOrNull()?.clear() ?: Unit

  /** Render report using specified renderer */
  fun dump(renderer: ReportRenderer): String =
    currentTestOrNull()?.let { renderer.render(it, collectSnapshots()) } ?: ""

  /** Render report only if there are failures */
  fun dumpIfFailed(renderer: ReportRenderer = PrettyConsoleRenderer): String =
    currentTestOrNull()
      ?.takeIf { it.hasFailures() }
      ?.let { renderer.render(it, collectSnapshots()) }
      ?: ""

  /** Print report to console only if there are failures */
  fun printIfFailed(renderer: ReportRenderer = PrettyConsoleRenderer): Unit =
    dumpIfFailed(renderer).takeIf { it.isNotEmpty() }?.let(::println) ?: Unit

  /** Collect snapshots from all reporting systems */
  fun collectSnapshots(): List<SystemSnapshot> = runCatching {
    if (Stove.instanceInitialized()) {
      Stove.instance.activeSystems.values
        .filterIsInstance<Reports>()
        .map { it.snapshot() }
    } else {
      emptyList()
    }
  }.getOrDefault(emptyList())

  private fun resolveTestId(): String? = StoveTestContextHolder.get()?.testId ?: contextThreadLocal.get()

  companion object {
    private const val DEFAULT_TEST_ID = "default"
  }
}
