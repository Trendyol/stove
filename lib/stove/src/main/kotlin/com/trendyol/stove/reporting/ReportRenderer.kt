package com.trendyol.stove.reporting

/**
 * Interface for rendering test reports in different formats.
 */
interface ReportRenderer {
  /**
   * Render a test report with optional system snapshots.
   */
  fun render(report: TestReport, snapshots: List<SystemSnapshot>): String

  /**
   * Apply this renderer's final output policy after other diagnostics have been appended.
   *
   * Composite reporters should call this after composing their complete output. Renderers without
   * a final size policy leave the output unchanged.
   */
  fun limitOutput(output: String): String = output
}
