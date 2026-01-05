package com.trendyol.stove.testing.e2e.reporting

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Container for report entries belonging to a single test.
 * Thread-safe for concurrent recording during test execution.
 *
 * Exposes only immutable views of data through public APIs.
 */
class TestReport(
  val testId: String,
  val testName: String
) {
  private val queue: ConcurrentLinkedQueue<ReportEntry> = ConcurrentLinkedQueue()

  /** Record a new entry. Thread-safe. */
  fun record(entry: ReportEntry): Unit = queue.add(entry).let { }

  /** All entries as immutable list */
  fun entries(): List<ReportEntry> = queue.toList()

  /** Entries for this test only */
  fun entriesForThisTest(): List<ReportEntry> = queue.filter { it.testId == testId }

  /** All failed entries */
  fun failures(): List<ReportEntry> = queue.filter { it.isFailed }

  /** Failed entries for this test */
  fun failuresForThisTest(): List<ReportEntry> = entriesForThisTest().filter { it.isFailed }

  /** True if any failures exist */
  fun hasFailures(): Boolean = queue.any { it.isFailed }

  /** Clear all entries */
  fun clear(): Unit = queue.clear()
}

// ============================================================================
// Extension Functions for List<ReportEntry>
// Functional-style filtering operations
// ============================================================================

/** Filter entries by system name */
fun List<ReportEntry>.forSystem(system: String): List<ReportEntry> =
  filter { it.system == system }

/** Filter entries by test ID */
fun List<ReportEntry>.forTest(testId: String): List<ReportEntry> =
  filter { it.testId == testId }

/** Get only failed entries */
fun List<ReportEntry>.failures(): List<ReportEntry> =
  filter { it.isFailed }

/** Get only passed entries */
fun List<ReportEntry>.passed(): List<ReportEntry> =
  filter { it.isPassed }

/** Get only action entries */
fun List<ReportEntry>.actions(): List<ActionEntry> =
  filterIsInstance<ActionEntry>()

/** Get only assertion entries */
fun List<ReportEntry>.assertions(): List<AssertionEntry> =
  filterIsInstance<AssertionEntry>()

/** Get entries with assertion results (passed or failed) */
fun List<ReportEntry>.withResults(): List<ReportEntry> =
  filter { it.result != null }
