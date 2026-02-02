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
@Suppress("TooManyFunctions")
object TraceReportBuilder {
  private const val SPAN_WAIT_TIME_MS = 500L
  private const val NO_SPANS_MESSAGE = "No spans in trace"

  // ANSI color codes for the header
  private object Colors {
    const val RESET = "\u001B[0m"
    const val BOLD = "\u001B[1m"
    const val CYAN = "\u001B[36m"
    const val BRIGHT_CYAN = "\u001B[96m"
  }

  const val DEFAULT_ERROR_MESSAGE = "Test failed"

  /**
   * Builds the full report including Stove execution report and trace tree.
   */
  fun buildFullReport(): String {
    val options = Stove.options()
    val report = Stove.reporter().dumpIfFailed(options.failureRenderer)
    val traceTree = getColoredTraceTreeIfEnabled()
    return buildReport(report, traceTree)
  }

  /**
   * Checks if failure enrichment should be performed based on options.
   */
  fun StoveOptions.shouldEnrichFailures(): Boolean =
    dumpReportOnTestFailure && reportingEnabled

  private fun getColoredTraceTreeIfEnabled(): String =
    TraceContext
      .current()
      .toOption()
      .flatMap { Stove.getSystemOrNone<TracingSystem>() }
      .flatMap { it.getTraceVisualizationForCurrentTest(SPAN_WAIT_TIME_MS) }
      .map { visualization ->
        // Use the colored tree for terminal display
        visualization.coloredTree.let { tree ->
          if (tree.isNotEmpty() && tree != NO_SPANS_MESSAGE) tree else ""
        }
      }.getOrElse { "" }

  private fun buildReport(stoveReport: String, traceTree: String): String = buildString {
    if (stoveReport.isNotEmpty()) {
      append(stoveReport)
    }
    if (traceTree.isNotEmpty() && traceTree != NO_SPANS_MESSAGE) {
      if (isNotEmpty()) appendLine().appendLine()
      appendLine(buildColoredHeader())
      append(traceTree)
    }
  }

  private fun buildColoredHeader(): String = buildString {
    val headerLine = "${Colors.CYAN}═══════════════════════════════════════════════════════════════${Colors.RESET}"
    val title = "${Colors.BOLD}${Colors.BRIGHT_CYAN}EXECUTION TRACE${Colors.RESET} (Call Chain)"
    appendLine(headerLine)
    appendLine(title)
    appendLine(headerLine)
  }
}
