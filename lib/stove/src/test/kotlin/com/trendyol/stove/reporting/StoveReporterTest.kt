package com.trendyol.stove.reporting

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

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
  })
