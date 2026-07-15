package com.trendyol.stove.scoping

import com.trendyol.stove.reporting.StoveTestContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class TestScopedJournalTest :
  FunSpec({
    test("untagged entries are visible to every test") {
      val journal = TestScopedJournal<String>()
      journal.record(null, "untagged")

      journal.entries("test-a") shouldContainExactly listOf("untagged")
      journal.entries("test-b") shouldContainExactly listOf("untagged")
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
