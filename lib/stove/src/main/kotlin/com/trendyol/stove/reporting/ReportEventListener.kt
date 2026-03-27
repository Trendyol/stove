package com.trendyol.stove.reporting

/**
 * Listener for report lifecycle events.
 *
 * Implementors receive callbacks when tests start, end, and when report entries are recorded.
 * All methods have default no-op implementations — override only what you need.
 *
 * Methods are non-suspending. Implementors should dispatch async work internally
 * if needed — the reporter will not wait.
 */
interface ReportEventListener {
  fun onTestStarted(ctx: StoveTestContext) {}
  fun onTestFailed(testId: String, error: String) {}
  fun onTestEnded(testId: String) {}
  fun onEntryRecorded(entry: ReportEntry) {}
}
