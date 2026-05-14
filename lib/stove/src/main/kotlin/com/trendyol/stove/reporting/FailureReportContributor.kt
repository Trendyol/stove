package com.trendyol.stove.reporting

/**
 * Adds optional sections to Stove's enriched failure report.
 *
 * Implementations must be fast and bounded because they run while a test failure
 * is being converted to the framework-specific exception.
 */
interface FailureReportContributor {
  fun contribute(testId: String): String
}
