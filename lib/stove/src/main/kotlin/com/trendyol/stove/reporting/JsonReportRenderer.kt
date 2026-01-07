package com.trendyol.stove.reporting

import arrow.core.Option
import arrow.core.getOrElse
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
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
    val entries = report.entries()
    val jsonReport = JsonTestReport(
      testId = report.testId,
      testName = report.testName,
      timestamp = timestampFormatter.format(Instant.now()),
      entries = entries.map { it.toJsonEntry() },
      systemSnapshots = snapshots.associate { it.system to it.state },
      summary = JsonSummary(
        total = entries.size,
        passed = entries.count { it.isPassed },
        failed = entries.count { it.isFailed }
      )
    )
    return mapper.writeValueAsString(jsonReport)
  }

  private fun ReportEntry.toJsonEntry(): JsonReportEntry = JsonReportEntry(
    timestamp = timestampFormatter.format(timestamp),
    system = system,
    testId = testId,
    action = action,
    input = input.toJsonValue(),
    output = output.toJsonValue(),
    metadata = metadata,
    expected = expected.toJsonValue(),
    actual = actual.toJsonValue(),
    result = result.name,
    error = error.toJsonValue()
  )

  /** Convert Option to JSON-friendly value - empty string for None */
  private fun <T : Any> Option<T>.toJsonValue(): Any = getOrElse { "" }
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
 * No nullable fields - uses empty string/map for absent values.
 */
data class JsonReportEntry(
  val timestamp: String,
  val system: String,
  val testId: String,
  val action: String,
  val input: Any,
  val output: Any,
  val metadata: Map<String, Any>,
  val expected: Any,
  val actual: Any,
  val result: String,
  val error: Any
)

/**
 * JSON representation of report summary.
 */
data class JsonSummary(
  val total: Int,
  val passed: Int,
  val failed: Int
)
