package com.trendyol.stove.reporting

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.rendering.BorderType.Companion.ROUNDED
import com.github.ajalt.mordant.rendering.BorderType.Companion.SQUARE
import com.github.ajalt.mordant.rendering.TextColors.brightBlue
import com.github.ajalt.mordant.rendering.TextColors.brightCyan
import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.rendering.TextColors.brightMagenta
import com.github.ajalt.mordant.rendering.TextColors.brightRed
import com.github.ajalt.mordant.rendering.TextColors.brightWhite
import com.github.ajalt.mordant.rendering.TextColors.brightYellow
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.rendering.Whitespace
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.table.verticalLayout
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Panel
import com.github.ajalt.mordant.widgets.Text

internal class MordantConsoleReportRenderer(
  limits: ConsoleReportLimits?
) : ReportRenderer {
  private val policy = ConsoleReportPolicy(limits)
  private val timelineRenderer = ConsoleTimelineRenderer(policy)
  private val snapshotRenderer = ConsoleSnapshotRenderer(policy)

  override fun render(report: TestReport, snapshots: List<SystemSnapshot>): String {
    val prepared = prepareReport(report, snapshots)
    val renderWidth = calculateRenderWidth(prepared)
    val terminal = createTerminal(renderWidth)
    val panelContentWidth = renderWidth - PANEL_CHROME_WIDTH
    val snapshotContentWidth = (renderWidth - NESTED_PANEL_CHROME_WIDTH)
      .coerceAtLeast(MIN_RENDER_WIDTH - PANEL_CHROME_WIDTH)

    val widgets = buildList {
      add(buildSummaryPanel(prepared, panelContentWidth))
      add(buildTimelinePanel(prepared, panelContentWidth))
      if (prepared.snapshots.isNotEmpty()) {
        add(buildSnapshotsPanel(prepared, snapshotContentWidth))
      }
    }

    return widgets
      .joinToString(separator = "\n\n") { terminal.render(it) }
      .let(::limitOutput)
  }

  override fun limitOutput(output: String): String =
    policy.limitOutput(output)

  private fun prepareReport(
    report: TestReport,
    snapshots: List<SystemSnapshot>
  ): PreparedReport {
    val entries = report.entries()
    val summary = SummaryStats.from(entries)
    val timeline = timelineRenderer.render(entries)
    val selectedSnapshots = policy.selectSnapshots(snapshots)
    return PreparedReport(
      report = report,
      entries = entries,
      timeline = timeline,
      summary = summary,
      summaryText = buildSummaryText(report, summary),
      snapshots = selectedSnapshots.snapshots.map { PreparedSnapshot(it, snapshotRenderer.render(it)) },
      totalSnapshots = snapshots.size,
      omittedSnapshots = selectedSnapshots.omittedSnapshots
    )
  }

  private fun buildSummaryPanel(prepared: PreparedReport, contentWidth: Int): Widget = Panel(
    title = Text((bold + brightWhite)("STOVE TEST EXECUTION REPORT")),
    bottomTitle = Text((bold + prepared.summary.statusColor)(prepared.summary.statusLabel)),
    borderType = ROUNDED,
    borderStyle = prepared.summary.borderColor,
    expand = true,
    content = Text(ConsoleTextWrapper.wrap(prepared.summaryText, contentWidth), whitespace = Whitespace.PRE)
  )

  private fun buildSummaryText(report: TestReport, summary: SummaryStats): String = buildString {
    appendLine("${bold("Test")}: ${brightYellow(report.testName)}")
    appendLine("${bold("ID")}: ${dim(report.testId)}")
    appendLine("${bold("Status")}: ${(bold + summary.statusColor)(summary.statusLabel)}")
    appendLine()
    appendLine(
      "${bold("Summary")}: " +
        brightGreen("${summary.passed} passed") +
        "  ·  " +
        (if (summary.failed > 0) brightRed("${summary.failed} failed") else brightGreen("0 failed")) +
        "  ·  " +
        brightCyan("${summary.total} total")
    )
  }.trimEnd()

  private fun buildTimelinePanel(prepared: PreparedReport, contentWidth: Int): Widget {
    val content =
      if (prepared.entries.isEmpty()) {
        Text(dim("No actions recorded yet."), whitespace = Whitespace.PRE)
      } else {
        Text(ConsoleTextWrapper.wrap(prepared.timeline.text, contentWidth), whitespace = Whitespace.PRE)
      }

    return Panel(
      title = Text((bold + brightCyan)("TIMELINE")),
      bottomTitle = Text(dim(timelineFooter(prepared))),
      borderType = ROUNDED,
      borderStyle = cyan,
      expand = true,
      content = content
    )
  }

  private fun timelineFooter(prepared: PreparedReport): String =
    if (prepared.timeline.omittedEntries > 0) {
      "${prepared.entries.size} step(s) · ${prepared.timeline.omittedEntries} omitted"
    } else {
      "${prepared.entries.size} step(s)"
    }

  private fun buildSnapshotsPanel(prepared: PreparedReport, contentWidth: Int): Widget = Panel(
    title = Text((bold + brightMagenta)("SYSTEM SNAPSHOTS")),
    bottomTitle = Text(dim(snapshotFooter(prepared))),
    borderType = ROUNDED,
    borderStyle = brightMagenta,
    expand = true,
    content = verticalLayout {
      spacing = 1
      if (prepared.omittedSnapshots > 0) {
        cell(Text(dim("… ${prepared.omittedSnapshots} snapshot(s) omitted from compact output")))
      }
      prepared.snapshots.forEach { cell(buildSnapshotPanel(it, contentWidth)) }
    }
  )

  private fun snapshotFooter(prepared: PreparedReport): String =
    if (prepared.omittedSnapshots > 0) {
      "${prepared.totalSnapshots} snapshot(s) · ${prepared.omittedSnapshots} omitted"
    } else {
      "${prepared.totalSnapshots} snapshot(s)"
    }

  private fun buildSnapshotPanel(prepared: PreparedSnapshot, contentWidth: Int): Widget = Panel(
    title = Text((bold + brightWhite)(prepared.snapshot.system.uppercase())),
    borderType = SQUARE,
    borderStyle = consoleStyleForSystem(prepared.snapshot.system),
    expand = true,
    content = Text(ConsoleTextWrapper.wrap(prepared.text, contentWidth), whitespace = Whitespace.PRE)
  )

  private fun calculateRenderWidth(prepared: PreparedReport): Int {
    val candidateLines = sequence {
      yield("STOVE TEST EXECUTION REPORT")
      yield(prepared.report.testName)
      yield(prepared.report.testId)
      yield(prepared.summary.statusLabel)
      yieldAll(prepared.summaryText.lines())
      yield("TIMELINE")
      yieldAll(prepared.timeline.text.lines())
      if (prepared.snapshots.isNotEmpty()) {
        yield("SYSTEM SNAPSHOTS")
        prepared.snapshots.forEach {
          yield(it.snapshot.system.uppercase())
          yieldAll(it.text.lines())
        }
      }
    }

    val longestLine = candidateLines.maxOfOrNull(ConsoleTextWrapper::visibleLength) ?: MIN_RENDER_WIDTH
    return (longestLine + PANEL_CHROME_WIDTH).coerceIn(MIN_RENDER_WIDTH, MAX_RENDER_WIDTH)
  }

  private fun createTerminal(width: Int): Terminal = Terminal(
    ansiLevel = AnsiLevel.TRUECOLOR,
    width = width,
    nonInteractiveWidth = width,
    interactive = true
  )

  private data class PreparedSnapshot(
    val snapshot: SystemSnapshot,
    val text: String
  )

  private data class PreparedReport(
    val report: TestReport,
    val entries: List<ReportEntry>,
    val timeline: RenderedTimeline,
    val summary: SummaryStats,
    val summaryText: String,
    val snapshots: List<PreparedSnapshot>,
    val totalSnapshots: Int,
    val omittedSnapshots: Int
  )

  private data class SummaryStats(
    val passed: Int,
    val failed: Int,
    val total: Int
  ) {
    val hasFailures: Boolean = failed > 0
    val statusLabel: String = if (hasFailures) "FAILED" else "IN PROGRESS"
    val statusColor: TextStyle = if (hasFailures) brightRed else brightBlue
    val borderColor: TextStyle = if (hasFailures) brightMagenta else brightCyan

    companion object {
      fun from(entries: List<ReportEntry>): SummaryStats {
        var passed = 0
        var failed = 0
        entries.forEach { entry ->
          if (entry.isFailed) failed++ else passed++
        }
        return SummaryStats(
          passed = passed,
          failed = failed,
          total = entries.size
        )
      }
    }
  }

  private companion object {
    const val MIN_RENDER_WIDTH = 72
    const val MAX_RENDER_WIDTH = 160
    const val PANEL_CHROME_WIDTH = 6
    const val NESTED_PANEL_CHROME_WIDTH = 12
  }
}
