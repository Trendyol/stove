package com.trendyol.stove.tracing

import com.trendyol.stove.system.StoveOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class TraceReportBuilderTest :
  FunSpec({

    context("StoveOptions.shouldEnrichFailures") {
      test("should return true when both dumpReportOnTestFailure and reportingEnabled are true") {
        val options = StoveOptions(
          dumpReportOnTestFailure = true,
          reportingEnabled = true
        )

        with(TraceReportBuilder) {
          options.shouldEnrichFailures().shouldBeTrue()
        }
      }

      test("should return false when dumpReportOnTestFailure is false") {
        val options = StoveOptions(
          dumpReportOnTestFailure = false,
          reportingEnabled = true
        )

        with(TraceReportBuilder) {
          options.shouldEnrichFailures().shouldBeFalse()
        }
      }

      test("should return false when reportingEnabled is false") {
        val options = StoveOptions(
          dumpReportOnTestFailure = true,
          reportingEnabled = false
        )

        with(TraceReportBuilder) {
          options.shouldEnrichFailures().shouldBeFalse()
        }
      }

      test("should return false when both are false") {
        val options = StoveOptions(
          dumpReportOnTestFailure = false,
          reportingEnabled = false
        )

        with(TraceReportBuilder) {
          options.shouldEnrichFailures().shouldBeFalse()
        }
      }
    }

    context("DEFAULT_ERROR_MESSAGE") {
      test("should have correct default value") {
        TraceReportBuilder.DEFAULT_ERROR_MESSAGE shouldBe "Test failed"
      }
    }

    // Note: buildFullReport() requires a running Stove instance with initialized
    // reporter and potentially tracing system. These are better tested as
    // integration tests in the example projects.
  })
