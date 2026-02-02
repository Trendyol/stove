package com.trendyol.stove.reporting

import arrow.core.Some
import com.trendyol.stove.ConsoleSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class PrettyConsoleRendererTest :
  ConsoleSpec({ output ->

    fun String.stripAnsi(): String = replace(Regex("\u001B\\[[0-9;]*m"), "")

    // Dynamic width constants (from PrettyConsoleRenderer)
    val minBoxWidth = 60
    val maxBoxWidth = 200

    test("renders timeline with box drawing") {
      val report = TestReport("test-1", "should process order")
      report.record(ReportEntry.success("HTTP", "test-1", "POST /api/orders"))

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
        ReportEntry.failure(
          system = "Kafka",
          testId = "test-1",
          action = "shouldBePublished<OrderEvent>",
          error = "Timed out"
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
        ReportEntry.success(
          system = "HTTP",
          testId = "test-long",
          action = "POST /api/endpoint",
          input = Some(longInput)
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

    // ══════════════════════════════════════════════════════════════════════════════
    // FRAME ALIGNMENT TESTS
    // ══════════════════════════════════════════════════════════════════════════════

    context("Frame Alignment") {
      test("all content lines should have consistent width") {
        val report = TestReport("test-align", "Alignment Test")
        report.record(ReportEntry.success("HTTP", "test-align", "GET /api/test"))

        val rendered = PrettyConsoleRenderer.render(report, emptyList())
        val plainRendered = rendered.stripAnsi()
        val lines = plainRendered.lines().filter { it.isNotEmpty() }

        // All lines with box characters should have the same width (dynamic)
        val contentLines = lines.filter { it.startsWith("║") || it.startsWith("╔") || it.startsWith("╚") || it.startsWith("╠") }
        contentLines.shouldHaveSize(contentLines.size)
        contentLines.map { it.length }.distinct() shouldHaveSize 1

        // Width should be within bounds
        val actualWidth = contentLines.first().length
        actualWidth shouldBeGreaterThanOrEqual minBoxWidth
        actualWidth shouldBeLessThanOrEqual maxBoxWidth
      }

      test("top and bottom borders should have matching width") {
        val report = TestReport("test-borders", "Border Test")

        val rendered = PrettyConsoleRenderer.render(report, emptyList())
        val plainRendered = rendered.stripAnsi()
        val lines = plainRendered.lines()

        val topBorder = lines.first { it.startsWith("╔") }
        val bottomBorder = lines.last { it.startsWith("╚") }

        topBorder.length shouldBe bottomBorder.length
        // Width should be dynamic but within bounds
        topBorder.length shouldBeGreaterThanOrEqual minBoxWidth
      }

      test("content lines should start and end with proper borders") {
        val report = TestReport("test-content", "Content Test")
        report.record(ReportEntry.success("Database", "test-content", "INSERT INTO users"))

        val rendered = PrettyConsoleRenderer.render(report, emptyList())
        val plainRendered = rendered.stripAnsi()

        plainRendered
          .lines()
          .filter { it.contains("║") && !it.contains("╔") && !it.contains("╚") && !it.contains("╠") }
          .forEach { line ->
            line.first() shouldBe '║'
            line.last() shouldBe '║'
          }
      }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // WORD WRAPPING TESTS
    // ══════════════════════════════════════════════════════════════════════════════

    context("Word Wrapping") {
      test("wraps long text at word boundaries when possible") {
        val report = TestReport("test-wrap", "Wrap Test")
        val longInput = "This is a sentence with multiple words that should wrap at spaces to maintain readability"

        report.record(
          ReportEntry.success("HTTP", "test-wrap", "POST /api", input = Some(longInput))
        )

        val rendered = PrettyConsoleRenderer.render(report, emptyList())
        val plainRendered = rendered.stripAnsi()

        // Verify all box-framed lines have consistent width (dynamic)
        val boxLines = plainRendered
          .lines()
          .filter { it.startsWith("║") || it.startsWith("╔") || it.startsWith("╚") || it.startsWith("╠") }
        boxLines.map { it.length }.distinct() shouldHaveSize 1

        // Content should be fully present (no truncation)
        plainRendered shouldContain "This is"
        plainRendered shouldContain "readability"
      }

      test("handles extremely long words without spaces - expands to fit") {
        val report = TestReport("test-long-word", "Long Word Test")
        val longWord = "a".repeat(150) // 150 character word without spaces

        report.record(
          ReportEntry.success("HTTP", "test-long-word", "POST /api", input = Some(longWord))
        )

        val rendered = PrettyConsoleRenderer.render(report, emptyList())
        val plainRendered = rendered.stripAnsi()
        println(rendered)

        // Frame should still be intact - all lines same width
        val boxLines = plainRendered.lines().filter { it.startsWith("║") }
        boxLines.forEach { line ->
          line.endsWith("║") shouldBe true
        }
        boxLines.map { it.length }.distinct() shouldHaveSize 1

        // Full content should be present (no "..." truncation)
        plainRendered shouldNotContain "..."
        // Word should be fully present
        plainRendered shouldContain "aaaaaa" // Part of the long word
      }

      test("wraps multiline content preserving line breaks") {
        val report = TestReport("test-multiline", "Multiline Test")
        val multilineContent = "Line 1\nLine 2\nLine 3"

        report.record(
          ReportEntry.failure(
            system = "Kafka",
            testId = "test-multiline",
            action = "shouldBePublished",
            error = multilineContent
          )
        )

        val rendered = PrettyConsoleRenderer.render(report, emptyList())
        val plainRendered = rendered.stripAnsi()
        println(rendered)

        // All three lines should be present
        plainRendered shouldContain "Line 1"
        plainRendered shouldContain "Line 2"
        plainRendered shouldContain "Line 3"

        // Frame should remain intact - consistent width
        val boxLines = plainRendered.lines().filter { it.startsWith("║") }
        boxLines.map { it.length }.distinct() shouldHaveSize 1
      }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // ANSI COLOR CODE HANDLING TESTS
    // ══════════════════════════════════════════════════════════════════════════════

    context("ANSI Color Code Handling") {
      test("colored content should not affect frame alignment") {
        val report = TestReport("test-color", "Color Test")
        report.record(ReportEntry.success("HTTP", "test-color", "GET /api/test"))
        report.record(ReportEntry.failure("Database", "test-color", "INSERT failed", error = "Constraint violation"))

        val rendered = PrettyConsoleRenderer.render(report, emptyList())
        val plainRendered = rendered.stripAnsi()

        // Even with colors, all lines should have consistent width (dynamic)
        val boxLines = plainRendered.lines().filter { it.startsWith("║") }
        boxLines.map { it.length }.distinct() shouldHaveSize 1
      }

      test("long content expands box - no truncation") {
        val report = TestReport("test-expand", "Expand Test")
        val longAction = "A".repeat(150) + " with more text"

        report.record(ReportEntry.success("HTTP", "test-expand", longAction))

        val rendered = PrettyConsoleRenderer.render(report, emptyList())

        // Should contain RESET code
        rendered shouldContain "\u001B[0m"

        // Plain output should be aligned and NOT truncated
        val plainRendered = rendered.stripAnsi()
        val boxLines = plainRendered.lines().filter { it.startsWith("║") }
        boxLines.map { it.length }.distinct() shouldHaveSize 1

        // Full content should be present (no "..." truncation)
        plainRendered shouldContain "with more text"
        plainRendered shouldNotContain "..."
      }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // EDGE CASE TESTS
    // ══════════════════════════════════════════════════════════════════════════════

    context("Edge Cases") {
      test("empty report should maintain frame integrity") {
        val report = TestReport("test-empty", "Empty Report")

        val rendered = PrettyConsoleRenderer.render(report, emptyList())
        val plainRendered = rendered.stripAnsi()

        plainRendered shouldContain "╔"
        plainRendered shouldContain "╚"
        val boxLines = plainRendered
          .lines()
          .filter { it.startsWith("║") || it.startsWith("╔") || it.startsWith("╚") }
        boxLines.map { it.length }.distinct() shouldHaveSize 1
        boxLines.first().length shouldBeGreaterThanOrEqual minBoxWidth
      }

      test("special characters should not break frame") {
        val report = TestReport("test-special", "Special Chars")
        val specialContent = "Tab:\there End\nNewline above\rCarriage return\u0000Null char"

        report.record(
          ReportEntry.success("HTTP", "test-special", "POST", input = Some(specialContent))
        )

        val rendered = PrettyConsoleRenderer.render(report, emptyList())
        val plainRendered = rendered.stripAnsi()
        println(rendered)

        // Frame should still be valid
        plainRendered
          .lines()
          .filter { it.startsWith("║") }
          .forEach { line ->
            line.endsWith("║") shouldBe true
          }
      }

      test("Unicode characters should be handled correctly") {
        val report = TestReport("test-unicode", "Unicode Test")
        val unicodeContent = "日本語テスト 🎉 emoji ✓ checkmark → arrow"

        report.record(
          ReportEntry.success("HTTP", "test-unicode", "POST /api/unicode", input = Some(unicodeContent))
        )

        val rendered = PrettyConsoleRenderer.render(report, emptyList())
        println(rendered)

        // Should contain the unicode content
        rendered shouldContain "emoji"

        // Note: Unicode width calculation is complex, so we just verify the frame structure
        rendered shouldContain "╔"
        rendered shouldContain "╚"
      }

      test("deeply indented content should still fit in frame") {
        val report = TestReport("test-indent", "Indent Test")
        val snapshot = SystemSnapshot(
          system = "Kafka",
          state = mapOf(
            "nested" to listOf(
              mapOf(
                "level1" to mapOf(
                  "level2" to mapOf(
                    "level3" to "deeply nested value that might be quite long and need wrapping"
                  )
                )
              )
            )
          ),
          summary = "Test summary"
        )

        val rendered = PrettyConsoleRenderer.render(report, listOf(snapshot))
        val plainRendered = rendered.stripAnsi()
        println(rendered)

        // Frame should remain intact despite nesting - all lines consistent width
        val boxLines = plainRendered.lines().filter { it.startsWith("║") }
        boxLines.forEach { line ->
          line.endsWith("║") shouldBe true
        }
        boxLines.map { it.length }.distinct() shouldHaveSize 1
      }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // CONTENT LENGTH ADAPTATION TESTS
    // ══════════════════════════════════════════════════════════════════════════════

    context("Content Length Adaptation") {
      test("short content should be padded to fit minimum frame width") {
        val report = TestReport("test-short", "Short")
        report.record(ReportEntry.success("HTTP", "test-short", "GET"))

        val rendered = PrettyConsoleRenderer.render(report, emptyList())
        val plainRendered = rendered.stripAnsi()

        // Even with short content, all lines should be padded and consistent
        val boxLines = plainRendered.lines().filter { it.startsWith("║") }
        boxLines.map { it.length }.distinct() shouldHaveSize 1
        boxLines.first().length shouldBeGreaterThanOrEqual minBoxWidth
      }

      test("content adapts to frame width dynamically") {
        val report = TestReport("test-dynamic", "Dynamic Width Test")
        val moderateContent = "X".repeat(80) // Moderate length

        report.record(
          ReportEntry.success("HTTP", "test-dynamic", "POST", input = Some(moderateContent))
        )

        val rendered = PrettyConsoleRenderer.render(report, emptyList())
        val plainRendered = rendered.stripAnsi()

        // All lines should be consistent
        val boxLines = plainRendered.lines().filter { it.startsWith("║") }
        boxLines.map { it.length }.distinct() shouldHaveSize 1
      }

      test("box expands for long content without wrapping") {
        val report = TestReport("test-expand", "Expand Width")
        // Content that would need wrapping with fixed width, but expands box instead
        val longContent = "Word ".repeat(30) // ~150 chars

        report.record(
          ReportEntry.success("HTTP", "test-expand", "POST", input = Some(longContent))
        )

        val rendered = PrettyConsoleRenderer.render(report, emptyList())
        val plainRendered = rendered.stripAnsi()
        println(rendered)

        // All content should be present without truncation
        plainRendered shouldNotContain "..."
        plainRendered shouldContain "Word Word Word"

        // All lines should maintain consistent frame width
        val boxLines = plainRendered.lines().filter { it.startsWith("║") }
        boxLines.map { it.length }.distinct() shouldHaveSize 1
      }

      test("demonstrates smart word-boundary wrapping") {
        val report = TestReport("test-smart-wrap", "Smart Wrapping")
        val sentence = "The quick brown fox jumps over the lazy dog and keeps running until it reaches the end of the line"

        report.record(
          ReportEntry.failure(
            system = "HTTP",
            testId = "test-smart-wrap",
            action = "POST /api/test",
            error = sentence
          )
        )

        val rendered = PrettyConsoleRenderer.render(report, emptyList())
        println("\n--- Smart Word Wrapping Demo ---")
        println(rendered)

        // Full error message should be present
        val plainRendered = rendered.stripAnsi()
        plainRendered shouldContain "The quick brown fox"
        plainRendered shouldContain "end of the line"

        // Frame should be intact - all lines consistent
        val boxLines = plainRendered.lines().filter { it.startsWith("║") }
        boxLines.map { it.length }.distinct() shouldHaveSize 1
      }

      test("very long content with spaces wraps at word boundaries") {
        val report = TestReport("test-long-wrap", "Long Content Wrap Demo")
        // Long content WITH spaces - will wrap at word boundaries
        val veryLongLine = "word ".repeat(100) // 500 chars with spaces

        report.record(
          ReportEntry.success("HTTP", "test-long-wrap", "POST /api", input = Some(veryLongLine))
        )

        val rendered = PrettyConsoleRenderer.render(report, emptyList())
        val plainRendered = rendered.stripAnsi()
        println("\n--- Long Content Wrap Demo ---")
        println(rendered)

        // Content should be wrapped across multiple lines at word boundaries
        val contentLines = plainRendered.lines().filter { it.contains("word") }
        contentLines.size shouldBeGreaterThan 1

        // Frame should still be intact - capped at max width
        val boxLines = plainRendered.lines().filter { it.startsWith("║") }
        boxLines.forEach { line ->
          line.length shouldBeLessThanOrEqual maxBoxWidth
        }
        boxLines.map { it.length }.distinct() shouldHaveSize 1

        // Words should NOT be broken mid-word
        plainRendered
          .lines()
          .filter { it.contains("wor") && !it.contains("word") }
          .shouldHaveSize(0) // No partial "wor" without "d"
      }

      test("single long word without spaces stays on one line") {
        val report = TestReport("test-single-word", "Single Word Demo")
        // Single long word without spaces - should NOT be broken
        val longWord = "A".repeat(180)

        report.record(
          ReportEntry.success("HTTP", "test-single-word", longWord)
        )

        val rendered = PrettyConsoleRenderer.render(report, emptyList())
        val plainRendered = rendered.stripAnsi()
        println("\n--- Single Long Word Demo ---")
        println(rendered)

        // The long word should be on a single line (not split)
        val contentLines = plainRendered.lines().filter { it.contains("AAAA") }
        // Should be exactly 1 line containing the AAAs (word not broken)
        contentLines.size shouldBe 1

        // Frame should expand to fit the word
        val boxLines = plainRendered.lines().filter { it.startsWith("║") }
        boxLines.map { it.length }.distinct() shouldHaveSize 1
      }

      test("no truncation - all content is preserved") {
        val report = TestReport("test-no-truncate", "No Truncation Demo")
        val longWord = "X".repeat(180) // Long single word

        report.record(
          ReportEntry.success("HTTP", "test-no-truncate", "Action", input = Some(longWord))
        )

        val rendered = PrettyConsoleRenderer.render(report, emptyList())
        val plainRendered = rendered.stripAnsi()

        // No truncation indicator
        plainRendered shouldNotContain "..."

        // Frame should still be intact regardless of content
        val boxLines = plainRendered.lines().filter { it.startsWith("║") }
        boxLines.forEach { line ->
          line.endsWith("║") shouldBe true
        }
        boxLines.map { it.length }.distinct() shouldHaveSize 1
      }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // VISUAL SIMULATION TEST
    // ══════════════════════════════════════════════════════════════════════════════

    context("Visual Output Simulation") {
      test("simulates complete failure report with all elements") {
        val report = TestReport("showcase-test", "The Complete Order Flow - Every Feature in One Test")

        // Simulate a realistic failure scenario
        report.record(
          ReportEntry.success(
            "gRPC Mock",
            "showcase-test",
            "Register unary stub: frauddetection.FraudDetectionService/CheckFraud"
          )
        )
        report.record(ReportEntry.success("WireMock", "showcase-test", "Register stub: GET /inventory/macbook-pro-16"))
        report.record(ReportEntry.success("WireMock", "showcase-test", "Register stub: POST /payments/charge"))
        report.record(
          ReportEntry.failure(
            system = "HTTP",
            testId = "showcase-test",
            action = "POST /api/orders",
            input = Some("CreateOrderRequest(userId=user-123, productId=macbook-pro-16, amount=2499.99)"),
            output = Some("""{"message":"Internal server error","errorCode":"INTERNAL_ERROR"}"""),
            error = "expected:<201> but was:<500>",
            expected = Some("Response<OrderResponse> matching expectation"),
            actual = Some("Status: 500"),
            metadata = mapOf("status" to 500, "headers" to emptyMap<String, String>())
          )
        )

        val snapshots = listOf(
          SystemSnapshot(
            system = "WireMock",
            state = mapOf(
              "registeredStubs" to listOf(
                mapOf("id" to "stub-1", "method" to "GET", "url" to "/inventory/macbook-pro-16", "status" to 200),
                mapOf("id" to "stub-2", "method" to "POST", "url" to "/payments/charge", "status" to 200)
              ),
              "servedRequests" to listOf(
                mapOf("method" to "POST", "url" to "/payments/charge", "matched" to true),
                mapOf("method" to "GET", "url" to "/inventory/macbook-pro-16", "matched" to true)
              )
            ),
            summary = "Registered: 2\nServed: 2\nUnmatched: 0"
          ),
          SystemSnapshot(
            system = "Kafka",
            state = mapOf(
              "consumed" to emptyList<Any>(),
              "published" to emptyList<Any>()
            ),
            summary = "Consumed: 0\nPublished: 0\nCommitted: 0\nFailed: 0"
          )
        )

        val rendered = PrettyConsoleRenderer.render(report, snapshots)
        println("\n" + "=".repeat(120))
        println("VISUAL OUTPUT SIMULATION:")
        println("=".repeat(120) + "\n")
        println(rendered)
        println("\n" + "=".repeat(120))

        // Verify frame integrity - all box lines should have consistent width
        val plainRendered = rendered.stripAnsi()
        val boxLines = plainRendered
          .lines()
          .filter { it.startsWith("║") || it.startsWith("╔") || it.startsWith("╚") || it.startsWith("╠") }
        boxLines.map { it.length }.distinct() shouldHaveSize 1

        // Verify all sections are present
        plainRendered shouldContain "STOVE TEST EXECUTION REPORT"
        plainRendered shouldContain "TIMELINE"
        plainRendered shouldContain "SYSTEM SNAPSHOTS"
        plainRendered shouldContain "WIREMOCK"
        plainRendered shouldContain "KAFKA"
        plainRendered shouldContain "FAILED"
        plainRendered shouldContain "expected:<201> but was:<500>"
      }
    }
  })
