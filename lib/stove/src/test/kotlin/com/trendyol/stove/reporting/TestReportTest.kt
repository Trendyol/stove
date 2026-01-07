package com.trendyol.stove.reporting

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class TestReportTest :
  FunSpec({

    test("records entries in chronological order") {
      val report = TestReport("test-1", "test")

      val entry1 = ReportEntry.success("HTTP", "test-1", "GET /api")
      val entry2 = ReportEntry.success("Kafka", "test-1", "Publish")
      val entry3 = ReportEntry.action("Kafka", "test-1", "consumed", passed = true)

      report.record(entry1)
      report.record(entry2)
      report.record(entry3)

      report.entries() shouldHaveSize 3
    }

    test("filters failures correctly") {
      val report = TestReport("test-1", "test")

      report.record(ReportEntry.action("HTTP", "test-1", "check", passed = true))
      report.record(ReportEntry.action("Kafka", "test-1", "check", passed = false))
      report.record(ReportEntry.failure("PostgreSQL", "test-1", "Query", "timeout"))

      report.failures() shouldHaveSize 2
      report.hasFailures() shouldBe true
    }

    test("filters entries by testId") {
      val report = TestReport("test-1", "test")

      report.record(ReportEntry.success("HTTP", "test-1", "action"))
      report.record(ReportEntry.success("HTTP", "test-2", "other test"))

      report.entries() shouldHaveSize 2
      report.entriesForThisTest() shouldHaveSize 1
      report.entriesForThisTest().all { it.testId == "test-1" } shouldBe true
    }

    test("clear removes all entries") {
      val report = TestReport("test-1", "test")
      report.record(ReportEntry.success("HTTP", "test-1", "action"))

      report.clear()

      report.entries() shouldBe emptyList()
    }

    test("extension functions filter correctly") {
      val entries = listOf(
        ReportEntry.success("HTTP", "test-1", "action"),
        ReportEntry.action("Kafka", "test-1", "check", passed = true),
        ReportEntry.action("HTTP", "test-1", "check", passed = false)
      )

      entries.forSystem("HTTP") shouldHaveSize 2
      entries.failures() shouldHaveSize 1
      entries.passed() shouldHaveSize 2
    }
  })
