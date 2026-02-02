package com.trendyol.stove.reporting

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class StoveTestExceptionsTest :
  FunSpec({

    context("StoveTestFailureException") {
      test("should extend AssertionError") {
        val exception = StoveTestFailureException(
          originalMessage = "Test failed",
          stoveReport = "Report content"
        )

        exception.shouldBeInstanceOf<AssertionError>()
      }

      test("should format message with original message and report") {
        val exception = StoveTestFailureException(
          originalMessage = "expected:<200> but was:<500>",
          stoveReport = "HTTP GET /api/test - FAILED"
        )

        exception.message shouldContain "expected:<200> but was:<500>"
        exception.message shouldContain "STOVE EXECUTION REPORT"
        exception.message shouldContain "HTTP GET /api/test - FAILED"
      }

      test("should preserve cause") {
        val cause = RuntimeException("Original error")
        val exception = StoveTestFailureException(
          originalMessage = "Test failed",
          stoveReport = "Report",
          cause = cause
        )

        exception.cause shouldBe cause
      }

      test("should copy stack trace from cause") {
        val cause = RuntimeException("Original error")
        val originalStackTrace = cause.stackTrace

        val exception = StoveTestFailureException(
          originalMessage = "Test failed",
          stoveReport = "Report",
          cause = cause
        )

        exception.stackTrace shouldBe originalStackTrace
      }

      test("should handle null cause") {
        val exception = StoveTestFailureException(
          originalMessage = "Test failed",
          stoveReport = "Report",
          cause = null
        )

        exception.cause shouldBe null
      }

      test("should include separator line in message") {
        val exception = StoveTestFailureException(
          originalMessage = "Test failed",
          stoveReport = "Report"
        )

        exception.message shouldContain "═══════════════════════════════════════════════════════════════════════════════"
      }
    }

    context("StoveTestErrorException") {
      test("should extend Exception") {
        val exception = StoveTestErrorException(
          originalMessage = "Error occurred",
          stoveReport = "Report content"
        )

        exception.shouldBeInstanceOf<Exception>()
      }

      test("should format message with original message and report") {
        val exception = StoveTestErrorException(
          originalMessage = "Connection refused",
          stoveReport = "Kafka publish - ERROR"
        )

        exception.message shouldContain "Connection refused"
        exception.message shouldContain "STOVE EXECUTION REPORT"
        exception.message shouldContain "Kafka publish - ERROR"
      }

      test("should preserve cause") {
        val cause = IllegalStateException("Invalid state")
        val exception = StoveTestErrorException(
          originalMessage = "Error occurred",
          stoveReport = "Report",
          cause = cause
        )

        exception.cause shouldBe cause
      }

      test("should copy stack trace from cause") {
        val cause = IllegalStateException("Invalid state")
        val originalStackTrace = cause.stackTrace

        val exception = StoveTestErrorException(
          originalMessage = "Error occurred",
          stoveReport = "Report",
          cause = cause
        )

        exception.stackTrace shouldBe originalStackTrace
      }

      test("should handle null cause") {
        val exception = StoveTestErrorException(
          originalMessage = "Error occurred",
          stoveReport = "Report",
          cause = null
        )

        exception.cause shouldBe null
      }
    }

    context("message formatting") {
      test("should handle multiline original message") {
        val exception = StoveTestFailureException(
          originalMessage = "Line 1\nLine 2\nLine 3",
          stoveReport = "Report"
        )

        exception.message shouldContain "Line 1"
        exception.message shouldContain "Line 2"
        exception.message shouldContain "Line 3"
      }

      test("should handle multiline report") {
        val exception = StoveTestFailureException(
          originalMessage = "Test failed",
          stoveReport = "Step 1: OK\nStep 2: FAILED\nStep 3: SKIPPED"
        )

        exception.message shouldContain "Step 1: OK"
        exception.message shouldContain "Step 2: FAILED"
        exception.message shouldContain "Step 3: SKIPPED"
      }

      test("should handle empty report") {
        val exception = StoveTestFailureException(
          originalMessage = "Test failed",
          stoveReport = ""
        )

        exception.message shouldContain "Test failed"
        exception.message shouldContain "STOVE EXECUTION REPORT"
      }

      test("should handle special characters in message") {
        val exception = StoveTestFailureException(
          originalMessage = "Expected: {\"id\": 1} but was: {\"id\": 2}",
          stoveReport = "JSON comparison failed"
        )

        exception.message shouldContain "{\"id\": 1}"
        exception.message shouldContain "{\"id\": 2}"
      }
    }
  })
