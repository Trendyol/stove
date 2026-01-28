package com.trendyol.stove.tracing

import arrow.core.getOrElse
import arrow.core.toOption
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.StoveOptions

/**
 * Builds trace-enriched failure reports for test extensions.
 *
 * This centralizes the common logic for building reports that include
 * both Stove's execution report and the execution trace tree.
 */
object TraceReportBuilder {
  private const val SPAN_WAIT_TIME_MS = 500L
  private const val NO_SPANS_MESSAGE = "No spans in trace"
  private const val TRACE_HEADER_LINE = "═══════════════════════════════════════════════════════════════"
  private const val TRACE_HEADER_TITLE = "EXECUTION TRACE (Call Chain)"

  const val DEFAULT_ERROR_MESSAGE = "Test failed"

  /**
   * Builds the full report including Stove execution report and trace tree.
   */
  fun buildFullReport(): String {
    val options = Stove.options()
    val report = Stove.reporter().dumpIfFailed(options.failureRenderer)
    val traceTree = getTraceTreeIfEnabled()
    return buildReport(report, traceTree)
  }

  /**
   * Checks if failure enrichment should be performed based on options.
   */
  fun StoveOptions.shouldEnrichFailures(): Boolean =
    dumpReportOnTestFailure && reportingEnabled

  private fun getTraceTreeIfEnabled(): String =
    TraceContext
      .current()
      .toOption()
      .flatMap { Stove.getSystemOrNone<TracingSystem>() }
      .flatMap { it.getTraceVisualizationForCurrentTest(SPAN_WAIT_TIME_MS) }
      .map { it.tree }
      .getOrElse { "" }

  private fun buildReport(stoveReport: String, traceTree: String): String = buildString {
    if (stoveReport.isNotEmpty()) {
      append(stoveReport)
    }
    if (traceTree.isNotEmpty() && traceTree != NO_SPANS_MESSAGE) {
      if (isNotEmpty()) appendLine().appendLine()
      appendLine(TRACE_HEADER_LINE)
      appendLine(TRACE_HEADER_TITLE)
      appendLine(TRACE_HEADER_LINE)
      append(traceTree)
    }
  }
}
