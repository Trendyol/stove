package com.trendyol.stove.reporting

import arrow.core.Option
import arrow.core.getOrElse
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
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.magenta
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.white
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.rendering.Whitespace
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.table.verticalLayout
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Panel
import com.github.ajalt.mordant.widgets.Text
import com.trendyol.stove.tracing.TraceVisualization
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Mordant-based renderer for rich, terminal-friendly Stove test reports.
 */
@Suppress("TooManyFunctions")
object PrettyConsoleRenderer : ReportRenderer {
  private const val MIN_RENDER_WIDTH = 72
  private const val MAX_RENDER_WIDTH = 160
  private const val PANEL_CHROME_WIDTH = 6
  private const val SNAPSHOT_INDENT_STEP = 4
  private const val DETAIL_INDENT_STEP = 2
  private const val VALUE_PREVIEW_LIMIT = 6

  private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

  private data class SummaryStats(
    val passed: Int,
    val failed: Int,
    val total: Int
  ) {
    val hasFailures: Boolean = failed > 0
    val statusLabel: String = if (hasFailures) "FAILED" else "IN PROGRESS"
    val statusColor: TextStyle = if (hasFailures) brightRed else brightBlue
    val borderColor: TextStyle = if (hasFailures) brightMagenta else brightCyan
  }

  private data class PreparedSnapshot(
    val snapshot: SystemSnapshot,
    val text: String
  )

  private data class PreparedReport(
    val report: TestReport,
    val entries: List<ReportEntry>,
    val summary: SummaryStats,
    val summaryText: String,
    val timelineText: String,
    val snapshots: List<PreparedSnapshot>
  )

  override fun render(report: TestReport, snapshots: List<SystemSnapshot>): String {
    val prepared = prepareReport(report, snapshots)
    val renderWidth = calculateRenderWidth(prepared)
    val terminal = createTerminal(renderWidth)

    val widgets = buildList {
      add(buildSummaryPanel(prepared))
      add(buildTimelinePanel(prepared))
      if (prepared.snapshots.isNotEmpty()) add(buildSnapshotsPanel(prepared.snapshots))
    }

    return widgets.joinToString(separator = "\n\n") { terminal.render(it) }
  }

  private fun prepareReport(report: TestReport, snapshots: List<SystemSnapshot>): PreparedReport {
    val entries = report.entries()
    val summary = buildSummaryStats(entries)
    return PreparedReport(
      report = report,
      entries = entries,
      summary = summary,
      summaryText = buildSummaryText(report, summary),
      timelineText = buildTimelineText(entries),
      snapshots = snapshots.map { PreparedSnapshot(it, buildSnapshotText(it)) }
    )
  }

  private fun buildSummaryStats(entries: List<ReportEntry>): SummaryStats = SummaryStats(
    passed = entries.count { it.isPassed },
    failed = entries.count { it.isFailed },
    total = entries.size
  )

  private fun createTerminal(width: Int): Terminal = Terminal(
    ansiLevel = AnsiLevel.TRUECOLOR,
    width = width,
    nonInteractiveWidth = width,
    interactive = true
  )

  private fun buildSummaryPanel(prepared: PreparedReport): Widget = Panel(
    title = Text((bold + brightWhite)("STOVE TEST EXECUTION REPORT")),
    bottomTitle = Text((bold + prepared.summary.statusColor)(prepared.summary.statusLabel)),
    borderType = ROUNDED,
    borderStyle = prepared.summary.borderColor,
    expand = true,
    content = Text(prepared.summaryText, whitespace = Whitespace.PRE)
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

  private fun buildTimelinePanel(prepared: PreparedReport): Widget {
    val content =
      if (prepared.entries.isEmpty()) {
        Text(dim("No actions recorded yet."), whitespace = Whitespace.PRE)
      } else {
        Text(prepared.timelineText, whitespace = Whitespace.PRE)
      }

    return Panel(
      title = Text((bold + brightCyan)("TIMELINE")),
      bottomTitle = Text(dim("${prepared.entries.size} step(s)")),
      borderType = ROUNDED,
      borderStyle = cyan,
      expand = true,
      content = content
    )
  }

  private fun calculateRenderWidth(prepared: PreparedReport): Int {
    val candidateLines = buildList {
      add("STOVE TEST EXECUTION REPORT")
      add(prepared.report.testName)
      add(prepared.report.testId)
      add(prepared.summary.statusLabel)
      addAll(prepared.summaryText.lines())
      add("TIMELINE")
      addAll(prepared.timelineText.lines())
      if (prepared.snapshots.isNotEmpty()) {
        add("SYSTEM SNAPSHOTS")
        prepared.snapshots.forEach { preparedSnapshot ->
          add(preparedSnapshot.snapshot.system.uppercase())
          addAll(preparedSnapshot.text.lines())
        }
      }
    }

    val longestLine = candidateLines.maxOfOrNull { visibleLength(it) } ?: MIN_RENDER_WIDTH
    return (longestLine + PANEL_CHROME_WIDTH).coerceIn(MIN_RENDER_WIDTH, MAX_RENDER_WIDTH)
  }

  private fun groupSequentialBySystem(entries: List<ReportEntry>): List<List<IndexedValue<ReportEntry>>> {
    val groups = mutableListOf<MutableList<IndexedValue<ReportEntry>>>()

    entries.withIndex().forEach { indexedEntry ->
      val lastGroup = groups.lastOrNull()
      if (lastGroup != null && lastGroup.first().value.system == indexedEntry.value.system) {
        lastGroup += indexedEntry
      } else {
        groups += mutableListOf(indexedEntry)
      }
    }

    return groups.map { it.toList() }
  }

  private fun buildTimelineText(entries: List<ReportEntry>): String =
    groupSequentialBySystem(entries)
      .flatMapIndexed { groupIndex, group ->
        val groupHeader = buildTimelineGroupHeader(group)
        val renderedEntries = group.flatMap { indexedEntry -> buildTimelineEntryLines(indexedEntry.index + 1, indexedEntry.value) }
        if (groupIndex == 0) listOf(groupHeader) + renderedEntries else listOf("", groupHeader) + renderedEntries
      }.joinToString("\n")

  private fun buildTimelineGroupHeader(group: List<IndexedValue<ReportEntry>>): String {
    val system = group.first().value.system
    val style = bold + styleForSystem(system)
    val failedCount = group.count { it.value.isFailed }
    val passedCount = group.size - failedCount
    val summary =
      if (failedCount > 0) {
        "${brightGreen("$passedCount passed")} · ${brightRed("$failedCount failed")}"
      } else {
        brightGreen("${group.size} passed")
      }

    return "${style("${system.uppercase()} · ${group.size} step(s)")}${dim("  $summary")}"
  }

  private fun buildTimelineEntryLines(index: Int, entry: ReportEntry): List<String> {
    val statusColor = if (entry.isFailed) brightRed else brightGreen
    val statusText = if (entry.isFailed) "✗ FAILED" else "✓ PASSED"
    val header = "  ${(bold + statusColor)(
      "#$index $statusText"
    )} ${brightWhite(sanitize(entry.action))} ${dim("(${formatTimestamp(entry)})")}"
    val details = buildEntryDetails(entry).lines().map { "      $it" }
    return listOf(header) + details
  }

  private fun buildEntryDetails(entry: ReportEntry): String = buildList {
    add("${brightCyan("Action")}: ${sanitize(entry.action)}")

    entry.input.fold({ }, { addAll(renderDetailBlock(yellow("Input"), it)) })
    entry.output.fold({ }, { addAll(renderDetailBlock(brightBlue("Output"), it)) })

    if (entry.metadata.isNotEmpty()) {
      addAll(renderDetailBlock(dim("Metadata"), entry.metadata))
    }

    if (entry.isFailed) {
      entry.expected.fold({ }, { addAll(renderDetailBlock(green("Expected"), it)) })
      entry.actual.fold({ }, { addAll(renderDetailBlock(red("Actual"), it)) })
      entry.error.fold({ }, { add("${brightRed("Error")}: ${sanitize(it)}") })
    }

    entry.executionTrace.fold({ }, { addAll(renderTraceDetails(it)) })
  }.joinToString("\n")

  private fun renderTraceDetails(trace: TraceVisualization): List<String> {
    val spanSummary =
      if (trace.failedSpans > 0) {
        "${trace.totalSpans} total / ${brightRed("${trace.failedSpans} failed")}"
      } else {
        "${trace.totalSpans} total / ${brightGreen("0 failed")}"
      }

    val styledTreeLines = trace.tree
      .lines()
      .map { line ->
        when {
          line.contains("✗") -> brightRed(line)
          line.contains("✓") -> brightGreen(line)
          line.startsWith("POST") || line.startsWith("GET") || line.startsWith("PUT") || line.startsWith("DELETE") -> brightCyan(line)
          line.trimStart().startsWith("|") -> magenta(line)
          else -> dim(line)
        }
      }

    return listOf(
      "",
      (bold + brightMagenta)("Execution Trace"),
      "${dim("TraceId")}: ${trace.traceId}",
      "${dim("Spans")}: $spanSummary"
    ) + styledTreeLines
  }

  private fun buildSnapshotsPanel(snapshots: List<PreparedSnapshot>): Widget = Panel(
    title = Text((bold + brightMagenta)("SYSTEM SNAPSHOTS")),
    bottomTitle = Text(dim("${snapshots.size} snapshot(s)")),
    borderType = ROUNDED,
    borderStyle = brightMagenta,
    expand = true,
    content = verticalLayout {
      spacing = 1
      snapshots.forEach { preparedSnapshot ->
        cell(buildSnapshotPanel(preparedSnapshot))
      }
    }
  )

  private fun buildSnapshotPanel(preparedSnapshot: PreparedSnapshot): Widget = Panel(
    title = Text((bold + brightWhite)(preparedSnapshot.snapshot.system.uppercase())),
    borderType = SQUARE,
    borderStyle = styleForSystem(preparedSnapshot.snapshot.system),
    expand = true,
    content = Text(preparedSnapshot.text, whitespace = Whitespace.PRE)
  )

  private fun buildSnapshotText(snapshot: SystemSnapshot): String {
    val summaryLines = snapshot.summary
      .lines()
      .map(::sanitize)
      .filter { it.isNotBlank() }

    val stateLines = renderSnapshotState(snapshot.state)

    return buildString {
      appendLine((bold + brightCyan)("Summary"))
      if (summaryLines.isEmpty()) {
        appendLine("  ${dim("No summary available")}")
      } else {
        summaryLines.forEach { appendLine("  ${styleSummaryLine(it)}") }
      }

      if (stateLines.isNotEmpty()) {
        appendLine()
        appendLine((bold + brightCyan)("State"))
        stateLines.forEach(::appendLine)
      }
    }.trimEnd()
  }

  private fun renderSnapshotState(state: Map<String, Any>, indent: Int = 4): List<String> =
    state.flatMap { (key, value) -> renderSnapshotEntry(key, value, indent) }

  private fun renderSnapshotEntry(key: String, value: Any?, indent: Int): List<String> {
    val prefix = " ".repeat(indent)
    val keyLabel = yellow(key)

    return when (value) {
      is Collection<*> -> {
        val count = "$prefix$keyLabel: ${styleCollectionCount(key, value.size)}"
        val items = value.flatMapIndexed { index, item -> renderSnapshotItem(index, item, indent + SNAPSHOT_INDENT_STEP) }
        listOf(count) + items
      }

      is Map<*, *> -> {
        val header = "$prefix$keyLabel:"
        val lines = value.entries.flatMap { (nestedKey, nestedValue) ->
          renderSnapshotEntry(nestedKey.toString(), nestedValue, indent + SNAPSHOT_INDENT_STEP)
        }
        listOf(header) + lines
      }

      else -> {
        listOf("$prefix$keyLabel: ${styleSnapshotValue(key, value)}")
      }
    }
  }

  private fun renderSnapshotItem(index: Int, item: Any?, indent: Int): List<String> {
    val prefix = " ".repeat(indent)
    val indexLabel = dim("[$index]")

    return when (item) {
      is Map<*, *> -> {
        val nested = item.entries.flatMap { (key, value) ->
          renderSnapshotEntry(key.toString(), value, indent + SNAPSHOT_INDENT_STEP)
        }
        listOf("$prefix$indexLabel") + nested
      }

      is Collection<*> -> {
        listOf("$prefix$indexLabel ${brightCyan("${item.size} item(s)")}")
      }

      else -> {
        listOf("$prefix$indexLabel ${formatValuePlain(item)}")
      }
    }
  }

  private fun renderDetailBlock(label: String, value: Any?): List<String> {
    val renderedValue = renderNestedValue(value)
    return if (renderedValue.size == 1) {
      listOf("$label: ${renderedValue.first().trimStart()}")
    } else {
      listOf("$label:") + renderedValue.map { "  $it" }
    }
  }

  private fun renderNestedValue(value: Any?, indent: Int = 0): List<String> {
    val prefix = " ".repeat(indent)
    return when (value) {
      null -> {
        listOf("${prefix}none")
      }

      is Option<*> -> {
        renderNestedValue(value.getOrElse { null }, indent)
      }

      is String -> {
        sanitize(value).lines().map { "$prefix$it" }
      }

      is Number, is Boolean -> {
        listOf("$prefix$value")
      }

      is Map<*, *> -> {
        if (value.isEmpty()) {
          listOf("$prefix{}")
        } else {
          value.entries.flatMap { (key, nestedValue) ->
            when (nestedValue) {
              is Map<*, *>, is Collection<*> -> listOf("$prefix$key:") + renderNestedValue(nestedValue, indent + DETAIL_INDENT_STEP)
              else -> listOf("$prefix$key: ${formatValuePlain(nestedValue)}")
            }
          }
        }
      }

      is Collection<*> -> {
        if (value.isEmpty()) {
          listOf("$prefix[]")
        } else {
          value.flatMapIndexed { index, item ->
            when (item) {
              is Map<*, *>, is Collection<*> -> listOf("$prefix[$index]") + renderNestedValue(item, indent + DETAIL_INDENT_STEP)
              else -> listOf("$prefix[$index] ${formatValuePlain(item)}")
            }
          }
        }
      }

      else -> {
        listOf("${prefix}${sanitize(value.toString())}")
      }
    }
  }

  private fun styleForSystem(system: String): TextStyle {
    val palette = listOf(brightBlue, brightMagenta, brightCyan, brightGreen, brightYellow)
    val index = (system.lowercase().hashCode() and Int.MAX_VALUE) % palette.size
    return palette[index]
  }

  private fun styleSummaryLine(line: String): String {
    val lower = line.lowercase()
    val number = extractLastNumber(lower)

    return when {
      "failed" in lower -> if ((number ?: 0) == 0) brightGreen(line) else brightRed(line)
      "passed" in lower || "success" in lower -> brightGreen(line)
      "consumed" in lower || "produced" in lower || "published" in lower || "registered" in lower || "served" in lower -> brightCyan(line)
      else -> white(line)
    }
  }

  private fun styleCollectionCount(key: String, size: Int): String {
    val lower = key.lowercase()
    return when {
      "fail" in lower -> if (size == 0) brightGreen("0 item(s)") else brightRed("$size item(s)")
      "pass" in lower || "success" in lower -> brightGreen("$size item(s)")
      else -> brightCyan("$size item(s)")
    }
  }

  private fun styleSnapshotValue(key: String, value: Any?): String {
    val lower = key.lowercase()

    return when (value) {
      is Number -> {
        val intValue = value.toInt()
        when {
          "fail" in lower -> if (intValue == 0) brightGreen(value.toString()) else brightRed(value.toString())
          "pass" in lower || "success" in lower -> brightGreen(value.toString())
          else -> brightYellow(value.toString())
        }
      }

      is Boolean -> {
        if (value) brightGreen("true") else brightRed("false")
      }

      else -> {
        formatValuePlain(value)
      }
    }
  }

  private fun formatTimestamp(entry: ReportEntry): String =
    entry.timestamp
      .atZone(ZoneId.systemDefault())
      .format(timeFormatter)

  private fun extractLastNumber(value: String): Int? =
    Regex("(\\d+)(?!.*\\d)")
      .find(value)
      ?.groupValues
      ?.getOrNull(1)
      ?.toIntOrNull()

  private fun formatValuePlain(value: Any?): String = when (value) {
    null -> "none"
    is Option<*> -> value.getOrElse { null }?.let(::formatValuePlain) ?: "none"
    is String -> sanitize(value)
    is Number, is Boolean -> value.toString()
    is Collection<*> -> renderCollection(value)
    is Map<*, *> -> renderMap(value)
    else -> sanitize(value.toString())
  }

  private fun renderCollection(value: Collection<*>): String {
    if (value.isEmpty()) return "[]"

    val printable = value.take(VALUE_PREVIEW_LIMIT)
    return printable.joinToString(", ", prefix = "[", postfix = if (value.size > VALUE_PREVIEW_LIMIT) ", ...]" else "]") {
      formatValuePlain(it)
    }
  }

  private fun renderMap(value: Map<*, *>): String {
    if (value.isEmpty()) return "{}"

    val printable = value.entries.take(VALUE_PREVIEW_LIMIT)
    return printable.joinToString(", ", prefix = "{", postfix = if (value.size > VALUE_PREVIEW_LIMIT) ", ...}" else "}") {
      "${it.key}=${formatValuePlain(it.value)}"
    }
  }

  private fun sanitize(value: String): String = value.replace("\r", "")

  private fun visibleLength(value: String): Int = stripAnsi(value).length

  private fun stripAnsi(value: String): String = value.replace(Regex("\u001B\\[[0-9;]*m"), "")
}
