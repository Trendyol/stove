package com.trendyol.stove.testing.e2e.reporting

import com.trendyol.stove.ConsoleSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant

class PrettyConsoleRendererTest :
  ConsoleSpec({ output ->

    fun String.stripAnsi(): String = replace(Regex("\u001B\\[[0-9;]*m"), "")

    test("renders timeline with box drawing") {
      val report = TestReport("test-1", "should process order")
      report.record(
        ActionEntry(
          timestamp = Instant.parse("2025-01-05T10:15:23.001Z"),
          system = "HTTP",
          testId = "test-1",
          action = "POST /api/orders"
        )
      )

      val rendered = PrettyConsoleRenderer.render(report, emptyList())
      println(rendered)

      val plainOutput = output.out.stripAnsi()
      plainOutput shouldContain "STOVE TEST EXECUTION REPORT"
      plainOutput shouldContain "should process order"
      plainOutput shouldContain "[HTTP] POST /api/orders"
      output.out shouldContain "╔"
      output.out shouldContain "╚"
    }

    test("highlights failed assertions") {
      val report = TestReport("test-1", "should fail")
      report.record(
        AssertionEntry(
          timestamp = Instant.now(),
          system = "Kafka",
          testId = "test-1",
          description = "shouldBePublished<OrderEvent>",
          result = AssertionResult.FAILED,
          failure = AssertionError("Timed out")
        )
      )

      val rendered = PrettyConsoleRenderer.render(report, emptyList())
      println(rendered)

      val plainOutput = output.out.stripAnsi()
      plainOutput shouldContain "✗"
      plainOutput shouldContain "FAILED"
      plainOutput shouldContain "Timed out"
    }

    test("renders system snapshots") {
      val report = TestReport("test-1", "test")
      val snapshot = SystemSnapshot(
        system = "Kafka",
        state = mapOf("consumed" to listOf(mapOf("topic" to "orders"))),
        summary = "Consumed: 1\nPublished: 0"
      )

      val rendered = PrettyConsoleRenderer.render(report, listOf(snapshot))
      println(rendered)

      val plainOutput = output.out.stripAnsi()
      plainOutput shouldContain "KAFKA"
      plainOutput shouldContain "Consumed: 1"
    }

    test("maintains frame integrity with long text") {
      val report = TestReport("test-long", "long test name")
      val longInput = "This is a very long input that exceeds the box width and should be wrapped properly"

      report.record(
        ActionEntry(
          timestamp = Instant.now(),
          system = "HTTP",
          testId = "test-long",
          action = "POST /api/endpoint",
          input = longInput
        )
      )

      val rendered = PrettyConsoleRenderer.render(report, emptyList())
      println(rendered)

      // Verify all content lines end with the closing border
      val plainRendered = rendered.stripAnsi()
      plainRendered
        .lines()
        .filter { it.startsWith("║") }
        .forEach { line ->
          line.endsWith("║") shouldBe true
        }
    }
  })
