package com.trendyol.stove.reporting

import com.github.ajalt.mordant.rendering.TextColors.brightBlue
import com.github.ajalt.mordant.rendering.TextColors.brightCyan
import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.rendering.TextColors.brightMagenta
import com.github.ajalt.mordant.rendering.TextColors.brightRed
import com.github.ajalt.mordant.rendering.TextColors.brightWhite
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.magenta
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.trendyol.stove.tracing.TraceVisualization
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class RenderedTimeline(
  val text: String,
  val omittedEntries: Int
)

internal class ConsoleTimelineRenderer(
  private val policy: ConsoleReportPolicy
) {
  private val values = ConsoleValueRenderer(policy)

  fun render(entries: List<ReportEntry>): RenderedTimeline {
    val visibleEntries = policy.selectTimelineEntries(entries)
    val omittedEntries = entries.size - visibleEntries.size
    val omissionNotice =
      if (omittedEntries > 0) {
        listOf(dim("… $omittedEntries step(s) omitted from compact output"), "")
      } else {
        emptyList()
      }
    val timeline = groupSequentialBySystem(visibleEntries)
      .flatMapIndexed { groupIndex, group -> renderGroup(groupIndex, group) }

    return RenderedTimeline(
      text = (omissionNotice + timeline).joinToString("\n"),
      omittedEntries = omittedEntries
    )
  }

  private fun renderGroup(
    groupIndex: Int,
    group: List<IndexedValue<ReportEntry>>
  ): List<String> {
    val header = buildGroupHeader(group)
    val entries = group.flatMap { buildEntryLines(it.index + 1, it.value) }
    return if (groupIndex == 0) listOf(header) + entries else listOf("", header) + entries
  }

  private fun groupSequentialBySystem(
    entries: List<IndexedValue<ReportEntry>>
  ): List<List<IndexedValue<ReportEntry>>> {
    val groups = mutableListOf<MutableList<IndexedValue<ReportEntry>>>()
    entries.forEach { indexedEntry ->
      val lastGroup = groups.lastOrNull()
      if (lastGroup != null && lastGroup.first().value.system == indexedEntry.value.system) {
        lastGroup += indexedEntry
      } else {
        groups += mutableListOf(indexedEntry)
      }
    }
    return groups.map { it.toList() }
  }

  private fun buildGroupHeader(group: List<IndexedValue<ReportEntry>>): String {
    val system = group.first().value.system
    val style = bold + consoleStyleForSystem(system)
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

  private fun buildEntryLines(index: Int, entry: ReportEntry): List<String> {
    val statusColor = if (entry.isFailed) brightRed else brightGreen
    val statusText = if (entry.isFailed) "✗ FAILED" else "✓ PASSED"
    val header = "  ${(bold + statusColor)(
      "#$index $statusText"
    )} ${brightWhite(values.sanitize(entry.action))} ${dim("(${formatTimestamp(entry)})")}"
    val details = buildEntryDetails(entry).lines().map { "      $it" }
    return listOf(header) + details
  }

  private fun buildEntryDetails(entry: ReportEntry): String = buildList {
    add("${brightCyan("Action")}: ${values.sanitize(entry.action)}")
    entry.input.fold({ }, { addAll(values.renderDetailBlock(yellow("Input"), it)) })
    entry.output.fold({ }, { addAll(values.renderDetailBlock(brightBlue("Output"), it)) })

    if (entry.metadata.isNotEmpty()) {
      addAll(values.renderDetailBlock(dim("Metadata"), entry.metadata))
    }
    if (entry.isFailed) {
      entry.expected.fold({ }, { addAll(values.renderDetailBlock(green("Expected"), it)) })
      entry.actual.fold({ }, { addAll(values.renderDetailBlock(red("Actual"), it)) })
      entry.error.fold({ }, { add("${brightRed("Error")}: ${values.sanitize(it)}") })
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
    val treeLines = values.sanitize(trace.tree)
      .lines()
      .map(::styleTraceLine)

    return listOf(
      "",
      (bold + brightMagenta)("Execution Trace"),
      "${dim("TraceId")}: ${trace.traceId}",
      "${dim("Spans")}: $spanSummary"
    ) + treeLines
  }

  private fun styleTraceLine(line: String): String = when {
    line.contains("✗") -> brightRed(line)
    line.contains("✓") -> brightGreen(line)
    HTTP_METHODS.any(line::startsWith) -> brightCyan(line)
    line.trimStart().startsWith("|") -> magenta(line)
    else -> dim(line)
  }

  private fun formatTimestamp(entry: ReportEntry): String =
    entry.timestamp
      .atZone(ZoneId.systemDefault())
      .format(TIME_FORMATTER)

  private companion object {
    val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    val HTTP_METHODS = listOf("POST", "GET", "PUT", "DELETE")
  }
}
