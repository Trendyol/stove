package com.trendyol.stove.scoping

import com.trendyol.stove.reporting.ReportEventListener
import com.trendyol.stove.reporting.StoveTestContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Fail-open, test-scoped store of evidence entries.
 *
 * An entry is excluded from a test's view only when it is provably tagged with a
 * different test id. Untagged entries (`testId == null`) can either remain suite-visible
 * (fixtures) or be constrained to overlapping test lifecycle windows (request evidence).
 *
 * Mock systems record what they observe here (requests, stubs, serve events) and read
 * back a per-test view for validation, verification, and snapshots.
 */
class TestScopedJournal<T> {
  private val tagged = ConcurrentHashMap<String, CopyOnWriteArrayList<T>>()
  private val untagged = CopyOnWriteArrayList<SequencedEntry<T>>()
  private val nextSequence = AtomicLong()
  private val testWindows = ConcurrentHashMap<String, TestWindow>()

  fun record(testId: String?, entry: T) {
    when (testId) {
      null -> untagged.add(SequencedEntry(nextSequence.getAndIncrement(), entry))
      else -> tagged.computeIfAbsent(testId) { CopyOnWriteArrayList() }.add(entry)
    }
  }

  /**
   * Entries visible without lifecycle scoping: the test's own plus every untagged entry.
   *
   * This is the appropriate view for suite-scoped fixtures such as untagged stubs. Request
   * evidence should use [entriesWithinTest] so traffic from completed tests is not replayed
   * into every later test.
   */
  fun entries(testId: String): List<T> =
    untagged.map(SequencedEntry<T>::value) + (tagged[testId]?.toList() ?: emptyList())

  /**
   * Request evidence visible to a test: its own tagged entries plus untagged entries observed
   * while that test was active. Concurrent tests intentionally see the same ambiguous untagged
   * evidence; tests that start later do not inherit traffic from an already completed test.
   *
   * If no lifecycle window is known (for example, a manually-created mock used without a Stove
   * test extension), this falls back to all untagged evidence to preserve fail-open behavior.
   */
  fun entriesWithinTest(testId: String): List<T> {
    val window = testWindows[testId]
    val visibleUntagged = if (window == null) {
      untagged
    } else {
      untagged.filter { entry ->
        entry.sequence >= window.startInclusive &&
          (window.endExclusive == null || entry.sequence < window.endExclusive)
      }
    }
    return visibleUntagged.map(SequencedEntry<T>::value) + (tagged[testId]?.toList() ?: emptyList())
  }

  /** Opens a new lifecycle window for untagged request evidence. */
  fun startTest(testId: String) {
    testWindows[testId] = TestWindow(startInclusive = nextSequence.get())
  }

  /** Closes the lifecycle window without discarding evidence needed by test-end listeners. */
  fun endTest(testId: String) {
    testWindows.computeIfPresent(testId) { _, window ->
      window.copy(endExclusive = nextSequence.get())
    }
  }

  /**
   * Drops untagged request evidence that cannot belong to any retained lifecycle window.
   * Call this after completed windows are cleared; suite-scoped fixture journals should not.
   */
  fun pruneUntaggedOutsideWindows() {
    val oldestRetainedSequence = testWindows.values.minOfOrNull(TestWindow::startInclusive)
    if (oldestRetainedSequence == null) {
      untagged.clear()
    } else {
      untagged.removeIf { it.sequence < oldestRetainedSequence }
    }
  }

  /**
   * Only entries provably owned by the given test — no untagged evidence. This is the
   * certainty-preserving view warnings are computed from, so shared fixtures and
   * non-propagating traffic can never raise a warning against an unrelated test.
   */
  fun taggedEntries(testId: String): List<T> = tagged[testId]?.toList() ?: emptyList()

  fun clear(testId: String) {
    tagged.remove(testId)
    testWindows.remove(testId)
  }

  fun clearAll() {
    tagged.clear()
    untagged.clear()
    testWindows.clear()
  }

  private data class SequencedEntry<T>(
    val sequence: Long,
    val value: T
  )

  private data class TestWindow(
    val startInclusive: Long,
    val endExclusive: Long? = null
  )
}

/**
 * Report listener that keeps [TestScopedJournal]s from accumulating for the suite
 * lifetime: when a test starts, entries of tests that have since completed are cleared,
 * as are stale entries of the starting test itself (from a previous retry of the same
 * test id). The journal decides whether untagged entries are suite fixtures or
 * lifecycle-windowed request evidence.
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
