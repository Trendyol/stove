package com.trendyol.stove.testing.e2e.reporting

import com.fasterxml.jackson.databind.*
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * JSON renderer for machine-parseable test reports.
 * Useful for CI integration, log aggregation, and programmatic analysis.
 */
object JsonReportRenderer : ReportRenderer {
  private val mapper = ObjectMapper().apply {
    enable(SerializationFeature.INDENT_OUTPUT)
  }

  private val timestampFormatter = DateTimeFormatter.ISO_INSTANT

  override fun render(report: TestReport, snapshots: List<SystemSnapshot>): String {
    val jsonReport = JsonTestReport(
      testId = report.testId,
      testName = report.testName,
      timestamp = timestampFormatter.format(Instant.now()),
      entries = report.entries().map { it.toJsonEntry() },
      systemSnapshots = snapshots.associate { it.system to it.state },
      summary = JsonSummary(
        totalActions = report.entries().filterIsInstance<ActionEntry>().size,
        totalAssertions = report.entries().filterIsInstance<AssertionEntry>().size,
        passedAssertions = report
          .entries()
          .filterIsInstance<AssertionEntry>()
          .count { it.result == AssertionResult.PASSED },
        failedAssertions = report.failures().size
      )
    )
    return mapper.writeValueAsString(jsonReport)
  }

  private fun ReportEntry.toJsonEntry(): JsonReportEntry = when (this) {
    is ActionEntry -> JsonReportEntry(
      type = if (result != null) "action_with_result" else "action",
      timestamp = timestampFormatter.format(timestamp),
      system = system,
      testId = testId,
      action = action,
      input = input,
      output = output,
      metadata = metadata,
      description = null,
      expected = expected,
      actual = actual,
      result = result?.name,
      error = error
    )

    is AssertionEntry -> JsonReportEntry(
      type = "assertion",
      timestamp = timestampFormatter.format(timestamp),
      system = system,
      testId = testId,
      action = null,
      input = null,
      output = null,
      metadata = emptyMap(),
      description = description,
      expected = expected,
      actual = actual,
      result = result.name,
      error = failure?.message
    )
  }
}

/**
 * JSON representation of a test report.
 */
data class JsonTestReport(
  val testId: String,
  val testName: String,
  val timestamp: String,
  val entries: List<JsonReportEntry>,
  val systemSnapshots: Map<String, Any>,
  val summary: JsonSummary
)

/**
 * JSON representation of a report entry.
 */
data class JsonReportEntry(
  val type: String,
  val timestamp: String,
  val system: String,
  val testId: String,
  val action: String?,
  val input: Any?,
  val output: Any?,
  val metadata: Map<String, Any>,
  val description: String?,
  val expected: Any?,
  val actual: Any?,
  val result: String?,
  val error: String?
)

/**
 * JSON representation of report summary.
 */
data class JsonSummary(
  val totalActions: Int,
  val totalAssertions: Int,
  val passedAssertions: Int,
  val failedAssertions: Int
)
