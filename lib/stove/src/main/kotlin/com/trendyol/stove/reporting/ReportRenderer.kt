package com.trendyol.stove.reporting

/**
 * Interface for rendering test reports in different formats.
 */
interface ReportRenderer {
  /**
   * Render a test report with optional system snapshots.
   */
  fun render(report: TestReport, snapshots: List<SystemSnapshot>): String
}
