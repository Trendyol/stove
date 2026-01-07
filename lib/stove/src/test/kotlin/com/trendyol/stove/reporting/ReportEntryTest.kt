package com.trendyol.stove.reporting

import arrow.core.Some
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ReportEntryTest :
  FunSpec({

    test("ReportEntry generates correct summary") {
      val entry = ReportEntry.success("HTTP", "test-1", "POST /api/users")

      entry.summary shouldBe "[HTTP] POST /api/users"
    }

    test("ReportEntry with failed result is detected as failure") {
      val entry = ReportEntry.failure(
        system = "PostgreSQL",
        testId = "test-1",
        action = "Query",
        error = "Row count mismatch"
      )

      entry.isFailed shouldBe true
      entry.isPassed shouldBe false
    }

    test("ReportEntry captures failure details with Option") {
      val entry = ReportEntry.action(
        system = "HTTP",
        testId = "test-1",
        action = "Response status check",
        passed = false,
        expected = Some(200),
        actual = Some(500),
        error = Some("Expected 200 but got 500")
      )

      entry.isFailed shouldBe true
      entry.error shouldBe Some("Expected 200 but got 500")
      entry.summary shouldBe "[HTTP] Response status check"
    }

    test("AssertionResult.of converts boolean correctly") {
      AssertionResult.of(true) shouldBe AssertionResult.PASSED
      AssertionResult.of(false) shouldBe AssertionResult.FAILED
    }
  })
