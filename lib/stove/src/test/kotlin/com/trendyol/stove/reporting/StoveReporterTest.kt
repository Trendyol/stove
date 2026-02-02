package com.trendyol.stove.reporting

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty

class StoveReporterTest :
  FunSpec({

    test("starts test and creates report") {
      val reporter = StoveReporter()
      val ctx = StoveTestContext("TestSpec::test1", "test1", "TestSpec")

      reporter.startTest(ctx)
      val report = reporter.currentTest()

      report.testId shouldBe "TestSpec::test1"
      report.testName shouldBe "test1"
    }

    test("records entries when enabled") {
      val reporter = StoveReporter()
      reporter.startTest(StoveTestContext("test-1", "test1"))

      reporter.record(ReportEntry.success("HTTP", "test-1", "GET /api"))

      reporter.currentTest().entries() shouldHaveSize 1
    }

    test("ignores entries when disabled") {
      val reporter = StoveReporter(isEnabled = false)
      reporter.startTest(StoveTestContext("test-1", "test1"))

      reporter.record(ReportEntry.success("HTTP", "test-1", "GET /api"))

      reporter.currentTest().entries() shouldHaveSize 0
    }

    test("uses default test ID when no context") {
      val reporter = StoveReporter()

      reporter.currentTestId() shouldBe "default"
      reporter.currentTest().testId shouldBe "default"
    }

    test("clears context after endTest") {
      val reporter = StoveReporter()
      reporter.startTest(StoveTestContext("test-1", "test1"))

      reporter.endTest()

      reporter.currentTestId() shouldBe "default"
    }

    test("detects failures correctly") {
      val reporter = StoveReporter()
      reporter.startTest(StoveTestContext("test-1", "test1"))

      reporter.hasFailures() shouldBe false

      reporter.record(ReportEntry.failure("HTTP", "test-1", "check", "assertion failed"))

      reporter.hasFailures() shouldBe true
    }

    test("currentTestOrNull returns null when no test started") {
      val reporter = StoveReporter()

      reporter.currentTestOrNull() shouldBe null
    }

    test("currentTestOrNull returns test after startTest") {
      val reporter = StoveReporter()
      reporter.startTest(StoveTestContext("test-1", "test1"))

      reporter.currentTestOrNull().shouldNotBeNull()
      reporter.currentTestOrNull()?.testId shouldBe "test-1"
    }

    test("clear removes entries from current test") {
      val reporter = StoveReporter()
      reporter.startTest(StoveTestContext("test-1", "test1"))
      reporter.record(ReportEntry.success("HTTP", "test-1", "GET /api"))

      reporter.currentTest().entries() shouldHaveSize 1

      reporter.clear()

      reporter.currentTest().entries() shouldHaveSize 0
    }

    test("dump returns empty string when no test exists") {
      val reporter = StoveReporter()

      val result = reporter.dump(PrettyConsoleRenderer)

      result shouldBe ""
    }

    test("dumpIfFailed returns empty string when no failures") {
      val reporter = StoveReporter()
      reporter.startTest(StoveTestContext("test-1", "test1"))
      reporter.record(ReportEntry.success("HTTP", "test-1", "GET /api"))

      val result = reporter.dumpIfFailed()

      result shouldBe ""
    }

    test("dumpIfFailed returns report when there are failures") {
      val reporter = StoveReporter()
      reporter.startTest(StoveTestContext("test-1", "test1"))
      reporter.record(ReportEntry.failure("HTTP", "test-1", "GET /api", "Not found"))

      val result = reporter.dumpIfFailed()

      result.shouldNotBeEmpty()
      result.replace(Regex("\u001B\\[[0-9;]*m"), "") shouldContain "FAILED"
    }

    test("collectSnapshots returns empty list when Stove not initialized") {
      val reporter = StoveReporter()

      val snapshots = reporter.collectSnapshots()

      snapshots shouldBe emptyList()
    }

    test("hasFailures returns false when no test context") {
      val reporter = StoveReporter()

      reporter.hasFailures() shouldBe false
    }

    test("multiple tests can be tracked independently") {
      val reporter = StoveReporter()

      reporter.startTest(StoveTestContext("test-1", "first test"))
      reporter.record(ReportEntry.success("HTTP", "test-1", "action1"))
      reporter.endTest()

      reporter.startTest(StoveTestContext("test-2", "second test"))
      reporter.record(ReportEntry.failure("Kafka", "test-2", "action2", "error"))
      reporter.endTest()

      // Start test-1 again to check its state
      reporter.startTest(StoveTestContext("test-1", "first test"))
      reporter.currentTest().entries() shouldHaveSize 1
      reporter.hasFailures() shouldBe false
    }
  })
