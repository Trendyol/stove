package com.trendyol.stove.scoping

import com.trendyol.stove.reporting.StoveTestContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TestScopedJournalTest :
  FunSpec({
    test("untagged entries are visible to every test") {
      val journal = TestScopedJournal<String>()
      journal.record(null, "untagged")

      journal.entries("test-a") shouldContainExactly listOf("untagged")
      journal.entries("test-b") shouldContainExactly listOf("untagged")
    }

    test("untagged request evidence is visible only inside a test lifecycle window") {
      val journal = TestScopedJournal<String>()
      journal.startTest("test-a")
      journal.record(null, "during-a")
      journal.endTest("test-a")
      journal.startTest("test-b")
      journal.record(null, "during-b")

      journal.entriesWithinTest("test-a") shouldContainExactly listOf("during-a")
      journal.entriesWithinTest("test-b") shouldContainExactly listOf("during-b")
    }

    test("concurrent tests share ambiguous untagged request evidence") {
      val journal = TestScopedJournal<String>()
      journal.startTest("test-a")
      journal.startTest("test-b")
      journal.record(null, "overlap")
      journal.endTest("test-a")
      journal.endTest("test-b")

      journal.entriesWithinTest("test-a") shouldContainExactly listOf("overlap")
      journal.entriesWithinTest("test-b") shouldContainExactly listOf("overlap")
    }

    test("completed request windows can be pruned without removing overlapping evidence") {
      val journal = TestScopedJournal<String>()
      journal.startTest("test-a")
      journal.record(null, "a-only")
      journal.startTest("test-b")
      journal.record(null, "overlap")
      journal.endTest("test-a")
      journal.clear("test-a")
      journal.pruneUntaggedOutsideWindows()

      journal.entriesWithinTest("test-b") shouldContainExactly listOf("overlap")
    }

    test("request evidence without lifecycle callbacks remains fail-open") {
      val journal = TestScopedJournal<String>()
      journal.record(null, "unscoped")

      journal.entriesWithinTest("manual-test") shouldContainExactly listOf("unscoped")
    }

    test("tagged entries are visible only to their test") {
      val journal = TestScopedJournal<String>()
      journal.record("test-a", "mine")

      journal.entries("test-a") shouldContainExactly listOf("mine")
      journal.entries("test-b") shouldBe emptyList()
    }

    test("clear removes only the given test's entries and keeps untagged") {
      val journal = TestScopedJournal<String>()
      journal.record(null, "untagged")
      journal.record("test-a", "mine")
      journal.clear("test-a")

      journal.entries("test-a") shouldContainExactly listOf("untagged")
    }

    test("clearAll removes everything") {
      val journal = TestScopedJournal<String>()
      journal.record(null, "untagged")
      journal.record("test-a", "mine")
      journal.clearAll()

      journal.entries("test-a") shouldBe emptyList()
    }

    test("concurrent request writes are retained in snapshot reads") {
      val journal = TestScopedJournal<Int>()
      val executor = Executors.newFixedThreadPool(8)
      try {
        repeat(1_000) { value ->
          executor.submit { journal.record(if (value % 2 == 0) "test-a" else null, value) }
        }
      } finally {
        executor.shutdown()
      }
      executor.awaitTermination(5, TimeUnit.SECONDS) shouldBe true

      journal.entries("test-a").toSet().size shouldBe 1_000
    }

    test("cleanup listener clears completed tests when the next test starts") {
      val cleared = mutableListOf<String>()
      val listener = TestScopeCleanupListener(cleared::add)

      listener.onTestStarted(StoveTestContext(testId = "test-1", testName = "test-1"))
      cleared shouldContainExactly listOf("test-1")

      listener.onTestEnded("test-1")
      listener.onTestStarted(StoveTestContext(testId = "test-2", testName = "test-2"))
      cleared shouldContainExactly listOf("test-1", "test-1", "test-2")
    }
  })
