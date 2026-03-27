package com.trendyol.stove.reporting

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class ReportEventListenerTest :
  FunSpec({

    test("listener receives all lifecycle events") {
      val reporter = StoveReporter()
      val events = mutableListOf<String>()
      val listener = object : ReportEventListener {
        override fun onTestStarted(ctx: StoveTestContext) {
          events.add("started:${ctx.testId}")
        }

        override fun onTestEnded(testId: String) {
          events.add("ended:$testId")
        }

        override fun onEntryRecorded(entry: ReportEntry) {
          events.add("entry:${entry.action}")
        }
      }

      reporter.addListener(listener)
      reporter.startTest(StoveTestContext("test-1", "test1"))
      reporter.record(ReportEntry.success("HTTP", "test-1", "GET /api"))
      reporter.endTest()

      events shouldBe listOf("started:test-1", "entry:GET /api", "ended:test-1")
    }

    test("throwing listener does not break reporter or other listeners") {
      val reporter = StoveReporter()
      val received = mutableListOf<String>()

      val brokenListener = object : ReportEventListener {
        override fun onTestStarted(ctx: StoveTestContext) {
          error("boom")
        }

        override fun onEntryRecorded(entry: ReportEntry) {
          error("boom")
        }

        override fun onTestEnded(testId: String) {
          error("boom")
        }
      }

      val goodListener = object : ReportEventListener {
        override fun onTestStarted(ctx: StoveTestContext) {
          received.add("started")
        }

        override fun onEntryRecorded(entry: ReportEntry) {
          received.add("entry")
        }

        override fun onTestEnded(testId: String) {
          received.add("ended")
        }
      }

      reporter.addListener(brokenListener)
      reporter.addListener(goodListener)

      reporter.startTest(StoveTestContext("test-1", "test1"))
      reporter.record(ReportEntry.success("HTTP", "test-1", "GET /api"))
      reporter.endTest()

      received shouldBe listOf("started", "entry", "ended")
    }

    test("removed listener stops receiving events") {
      val reporter = StoveReporter()
      val events = mutableListOf<String>()
      val listener = object : ReportEventListener {
        override fun onEntryRecorded(entry: ReportEntry) {
          events.add(entry.action)
        }
      }

      reporter.addListener(listener)
      reporter.startTest(StoveTestContext("test-1", "test1"))
      reporter.record(ReportEntry.success("HTTP", "test-1", "first"))

      reporter.removeListener(listener)
      reporter.record(ReportEntry.success("HTTP", "test-1", "second"))

      events shouldHaveSize 1
      events[0] shouldBe "first"
    }
  })
