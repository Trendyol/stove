package com.trendyol.stove.reporting

import com.trendyol.stove.system.Stove
import java.util.concurrent.*

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
  private val logger = org.slf4j.LoggerFactory.getLogger(StoveReporter::class.java)
  private val reports = ConcurrentHashMap<String, TestReport>()
  private val contextThreadLocal = ThreadLocal<String>()
  private val listeners = CopyOnWriteArrayList<ReportEventListener>()

  /** Register a listener to receive report events */
  fun addListener(listener: ReportEventListener) {
    listeners.add(listener)
  }

  /** Remove a previously registered listener */
  fun removeListener(listener: ReportEventListener) {
    listeners.remove(listener)
  }

  /** Start tracking a new test */
  fun startTest(ctx: StoveTestContext) {
    contextThreadLocal.set(ctx.testId)
    reports.computeIfAbsent(ctx.testId) { TestReport(ctx.testId, ctx.testName) }
    listeners.forEach {
      runCatching { it.onTestStarted(ctx) }.onFailure { e -> logger.warn("Listener failed on onTestStarted", e) }
    }
  }

  /** Mark the current test as failed */
  fun reportFailure(error: String) {
    val testId = resolveTestId() ?: return
    listeners.forEach {
      runCatching { it.onTestFailed(testId, error) }.onFailure { e -> logger.warn("Listener failed on onTestFailed", e) }
    }
  }

  /** End tracking the current test */
  fun endTest() {
    val testId = resolveTestId()
    try {
      if (testId != null) {
        listeners.forEach {
          runCatching { it.onTestEnded(testId) }.onFailure { e -> logger.warn("Listener failed on onTestEnded", e) }
        }
      }
    } finally {
      contextThreadLocal.remove()
    }
  }

  /** Record an entry in the current test's report */
  fun record(entry: ReportEntry) {
    if (!isEnabled) return
    currentTest().record(entry)
    listeners.forEach {
      runCatching { it.onEntryRecorded(entry) }.onFailure { e -> logger.warn("Listener failed on onEntryRecorded", e) }
    }
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
  fun clear(): Unit = resolveTestId()?.let(::clear) ?: Unit

  /** Clear report for the specified test ID */
  fun clear(testId: String): Unit = reports.remove(testId)?.clear() ?: Unit

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
      Stove.instance.systemsOf<Reports>()
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
