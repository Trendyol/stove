package com.trendyol.stove.testing.e2e.reporting

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class ReportEntryTest :
  FunSpec({

    test("ActionEntry generates correct summary") {
      val entry = ActionEntry(
        timestamp = Instant.now(),
        system = "HTTP",
        testId = "test-1",
        action = "POST /api/users"
      )

      entry.summary shouldBe "[HTTP] POST /api/users"
    }

    test("ActionEntry with failed result is detected as failure") {
      val entry = ActionEntry(
        timestamp = Instant.now(),
        system = "PostgreSQL",
        testId = "test-1",
        action = "Query",
        result = AssertionResult.FAILED,
        error = "Row count mismatch"
      )

      entry.isFailed shouldBe true
      entry.isPassed shouldBe false
    }

    test("AssertionEntry captures failure details") {
      val error = AssertionError("Expected 200 but got 500")
      val entry = AssertionEntry(
        timestamp = Instant.now(),
        system = "HTTP",
        testId = "test-1",
        description = "Response status check",
        expected = 200,
        actual = 500,
        result = AssertionResult.FAILED,
        failure = error
      )

      entry.isFailed shouldBe true
      entry.failure?.message shouldBe "Expected 200 but got 500"
      entry.summary shouldBe "[HTTP] Response status check: FAILED"
    }

    test("AssertionResult.of converts boolean correctly") {
      AssertionResult.of(true) shouldBe AssertionResult.PASSED
      AssertionResult.of(false) shouldBe AssertionResult.FAILED
    }
  })
