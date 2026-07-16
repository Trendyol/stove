package com.trendyol.stove.scoping

import com.trendyol.stove.reporting.ReportEventListener
import com.trendyol.stove.reporting.StoveTestContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fail-open, test-scoped store of evidence entries.
 *
 * An entry is excluded from a test's view only when it is provably tagged with a
 * different test id. Untagged entries (`testId == null`) are visible to every test,
 * so evidence sources without test-id propagation behave as if scoping did not exist.
 *
 * Mock systems record what they observe here (requests, stubs, serve events) and read
 * back a per-test view for validation, verification, and snapshots.
 */
class TestScopedJournal<T> {
  private val tagged = ConcurrentHashMap<String, CopyOnWriteArrayList<T>>()
  private val untagged = CopyOnWriteArrayList<T>()

  fun record(testId: String?, entry: T) {
    when (testId) {
      null -> untagged.add(entry)
      else -> tagged.computeIfAbsent(testId) { CopyOnWriteArrayList() }.add(entry)
    }
  }

  /** Entries visible to the given test: its own plus every untagged entry. */
  fun entries(testId: String): List<T> =
    untagged.toList() + (tagged[testId]?.toList() ?: emptyList())

  /**
   * Only entries provably owned by the given test — no untagged evidence. This is the
   * certainty-preserving view warnings are computed from, so shared fixtures and
   * non-propagating traffic can never raise a warning against an unrelated test.
   */
  fun taggedEntries(testId: String): List<T> = tagged[testId]?.toList() ?: emptyList()

  fun clear(testId: String) {
    tagged.remove(testId)
  }

  fun clearAll() {
    tagged.clear()
    untagged.clear()
  }
}

/**
 * Report listener that keeps [TestScopedJournal]s from accumulating for the suite
 * lifetime: when a test starts, entries of tests that have since completed are cleared,
 * as are stale entries of the starting test itself (from a previous retry of the same
 * test id). Untagged entries are never cleared — they belong to every test.
 */
class TestScopeCleanupListener(
  private val clear: (String) -> Unit
) : ReportEventListener {
  private val completedTestIds = ConcurrentLinkedQueue<String>()

  override fun onTestStarted(ctx: StoveTestContext) {
    while (true) {
      val testId = completedTestIds.poll() ?: break
      clear(testId)
    }
    clear(ctx.testId)
  }

  override fun onTestEnded(testId: String) {
    completedTestIds.add(testId)
  }
}
