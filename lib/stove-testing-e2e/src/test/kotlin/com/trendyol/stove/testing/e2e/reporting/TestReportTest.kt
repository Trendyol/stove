package com.trendyol.stove.testing.e2e.reporting

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Instant

class TestReportTest :
  FunSpec({

    test("records entries in chronological order") {
      val report = TestReport("test-1", "test")

      val entry1 = ActionEntry(Instant.now().minusSeconds(2), "HTTP", "test-1", "GET /api")
      val entry2 = ActionEntry(Instant.now().minusSeconds(1), "Kafka", "test-1", "Publish")
      val entry3 = AssertionEntry(Instant.now(), "Kafka", "test-1", "consumed", result = AssertionResult.PASSED)

      report.record(entry1)
      report.record(entry2)
      report.record(entry3)

      report.entries() shouldHaveSize 3
      report.entries()[0] shouldBe entry1
    }

    test("filters failures correctly") {
      val report = TestReport("test-1", "test")

      report.record(AssertionEntry(Instant.now(), "HTTP", "test-1", "check", result = AssertionResult.PASSED))
      report.record(AssertionEntry(Instant.now(), "Kafka", "test-1", "check", result = AssertionResult.FAILED))
      report.record(
        ActionEntry(
          Instant.now(),
          "PostgreSQL",
          "test-1",
          "Query",
          result = AssertionResult.FAILED
        )
      )

      report.failures() shouldHaveSize 2
      report.hasFailures() shouldBe true
    }

    test("filters entries by testId") {
      val report = TestReport("test-1", "test")

      report.record(ActionEntry(Instant.now(), "HTTP", "test-1", "action"))
      report.record(ActionEntry(Instant.now(), "HTTP", "test-2", "other test"))

      report.entries() shouldHaveSize 2
      report.entriesForThisTest() shouldHaveSize 1
      report.entriesForThisTest().all { it.testId == "test-1" } shouldBe true
    }

    test("clear removes all entries") {
      val report = TestReport("test-1", "test")
      report.record(ActionEntry(Instant.now(), "HTTP", "test-1", "action"))

      report.clear()

      report.entries() shouldBe emptyList()
    }

    test("extension functions filter correctly") {
      val entries = listOf(
        ActionEntry(Instant.now(), "HTTP", "test-1", "action"),
        AssertionEntry(Instant.now(), "Kafka", "test-1", "check", result = AssertionResult.PASSED),
        AssertionEntry(Instant.now(), "HTTP", "test-1", "check", result = AssertionResult.FAILED)
      )

      entries.forSystem("HTTP") shouldHaveSize 2
      entries.actions() shouldHaveSize 1
      entries.assertions() shouldHaveSize 2
      entries.failures() shouldHaveSize 1
      entries.passed() shouldHaveSize 1
    }
  })
