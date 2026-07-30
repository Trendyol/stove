package com.trendyol.stove.reporting

/**
 * Mordant-based renderer for rich, terminal-friendly Stove test reports.
 *
 * The object is the complete-output renderer and also exposes factories for compact and
 * environment-aware variants.
 */
object PrettyConsoleRenderer : ReportRenderer {
  private val completeRenderer: ReportRenderer = MordantConsoleReportRenderer(limits = null)

  override fun render(report: TestReport, snapshots: List<SystemSnapshot>): String =
    completeRenderer.render(report, snapshots)

  /**
   * Creates a renderer that keeps failed/recent timeline entries, bounds diagnostic containers,
   * shortens large values, and caps the final output. Complete counts and omission notices remain
   * visible.
   */
  fun compact(limits: ConsoleReportLimits = ConsoleReportLimits()): ReportRenderer =
    MordantConsoleReportRenderer(limits)

  /**
   * Creates a renderer that uses compact output on CI and the complete report elsewhere.
   */
  fun ciAware(limits: ConsoleReportLimits = ConsoleReportLimits()): ReportRenderer =
    ciAware(limits, ::isRunningOnCI)

  internal fun ciAware(
    limits: ConsoleReportLimits,
    isRunningOnCI: () -> Boolean
  ): ReportRenderer = CiAwareConsoleReportRenderer(limits, isRunningOnCI)
}

private class CiAwareConsoleReportRenderer(
  limits: ConsoleReportLimits,
  private val isRunningOnCI: () -> Boolean
) : ReportRenderer {
  private val complete: ReportRenderer = MordantConsoleReportRenderer(limits = null)
  private val compact: ReportRenderer = MordantConsoleReportRenderer(limits)

  override fun render(report: TestReport, snapshots: List<SystemSnapshot>): String =
    delegate().render(report, snapshots)

  override fun limitOutput(output: String): String =
    delegate().limitOutput(output)

  private fun delegate(): ReportRenderer =
    if (isRunningOnCI()) compact else complete
}
