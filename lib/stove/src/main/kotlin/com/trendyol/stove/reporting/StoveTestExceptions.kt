package com.trendyol.stove.reporting

/**
 * Exception that wraps test assertion failures with Stove's execution report.
 * The report is included in the exception message for display by test engines.
 *
 * Preserves the original exception's stack trace so test frameworks show the actual failure location.
 */
class StoveTestFailureException(
  originalMessage: String,
  stoveReport: String,
  cause: Throwable? = null
) : AssertionError(buildStoveReportMessage(originalMessage, stoveReport), cause) {
  init {
    // Copy the original stack trace to show the actual failure location
    cause?.let { stackTrace = it.stackTrace }
  }
}

/**
 * Exception that wraps test errors with Stove's execution report.
 * The report is included in the exception message for display by test engines.
 *
 * Preserves the original exception's stack trace so test frameworks show the actual failure location.
 */
class StoveTestErrorException(
  originalMessage: String,
  stoveReport: String,
  cause: Throwable? = null
) : Exception(buildStoveReportMessage(originalMessage, stoveReport), cause) {
  init {
    // Copy the original stack trace to show the actual failure location
    cause?.let { stackTrace = it.stackTrace }
  }
}

private fun buildStoveReportMessage(
  originalMessage: String,
  stoveReport: String
): String = """
  |$originalMessage
  |
  |═══════════════════════════════════════════════════════════════════════════════
  |                         STOVE EXECUTION REPORT
  |═══════════════════════════════════════════════════════════════════════════════
  |
  |$stoveReport
  """.trimMargin()
