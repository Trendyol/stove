package com.trendyol.stove.reporting

import arrow.core.Option
import arrow.core.getOrElse
import com.trendyol.stove.tracing.TraceVisualization
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pretty console renderer for human-readable test reports.
 *
 * Features:
 * - Dynamic box width that adapts to content (60-200 chars)
 * - Smart word wrapping at spaces only (never breaks mid-word)
 * - No truncation - all content is fully preserved
 * - Color-coded output for easy reading
 * - Box drawing characters for clean visual structure
 *
 * Implemented with pure functional programming - no mutation, no vars, no side effects.
 */
@Suppress("MagicNumber", "TooManyFunctions")
object PrettyConsoleRenderer : ReportRenderer {
  // ══════════════════════════════════════════════════════════════════════════════
  // CONSTANTS
  // ══════════════════════════════════════════════════════════════════════════════

  private const val MIN_BOX_WIDTH = 60
  private const val MAX_BOX_WIDTH = 200
  private const val BORDER_PADDING = 4 // "║ " + " ║"
  private const val INDENT_PADDING = 8 // Extra padding for indented content

  private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

  // ══════════════════════════════════════════════════════════════════════════════
  // ANSI COLORS
  // ══════════════════════════════════════════════════════════════════════════════

  private object Colors {
    const val RESET = "\u001B[0m"
    const val BOLD = "\u001B[1m"
    const val DIM = "\u001B[2m"
    const val RED = "\u001B[31m"
    const val GREEN = "\u001B[32m"
    const val YELLOW = "\u001B[33m"
    const val MAGENTA = "\u001B[35m"
    const val CYAN = "\u001B[36m"
    const val WHITE = "\u001B[37m"
    const val BRIGHT_RED = "\u001B[91m"
    const val BRIGHT_GREEN = "\u001B[92m"
    const val BRIGHT_YELLOW = "\u001B[93m"
    const val BRIGHT_BLUE = "\u001B[94m"
    const val BRIGHT_CYAN = "\u001B[96m"
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // BOX DIMENSIONS
  // ══════════════════════════════════════════════════════════════════════════════

  /**
   * Holds the calculated box dimensions for consistent rendering.
   */
  private data class BoxDimensions(
    val boxWidth: Int,
    val contentWidth: Int
  )

  /**
   * Calculate optimal box dimensions based on content.
   * Ensures the box is wide enough to fit:
   * 1. The longest content line
   * 2. The longest word (to avoid breaking words mid-word)
   */
  private fun calculateBoxDimensions(report: TestReport, snapshots: List<SystemSnapshot>): BoxDimensions {
    val contentLines = collectAllContentLines(report, snapshots)

    val longestWord = findLongestWord(contentLines)
    val longestLine = contentLines.maxOfOrNull { stripAnsi(it).length } ?: 0

    // Ensure we can fit the longest word with indent padding
    val requiredContentWidth = maxOf(longestLine, longestWord + INDENT_PADDING)
    val calculatedBoxWidth = (requiredContentWidth + BORDER_PADDING).coerceIn(MIN_BOX_WIDTH, MAX_BOX_WIDTH)

    return BoxDimensions(
      boxWidth = calculatedBoxWidth,
      contentWidth = calculatedBoxWidth - BORDER_PADDING
    )
  }

  /**
   * Collect all content lines for width calculation.
   */
  private fun collectAllContentLines(report: TestReport, snapshots: List<SystemSnapshot>): List<String> =
    buildList {
      // Header
      add("Test: ${report.testName}")
      add("ID: ${report.testId}")
      add("Status: FAILED")

      // Entries
      report.entries().forEach { entry ->
        add("00:00:00.000 ✓ PASSED [${entry.system}] ${entry.action}")
        entry.input.onSome { add("    Input: ${formatValuePlain(it)}") }
        entry.output.onSome { add("    Output: ${formatValuePlain(it)}") }
        entry.expected.onSome { add("    Expected: ${formatValuePlain(it)}") }
        entry.actual.onSome { add("    Actual: ${formatValuePlain(it)}") }
        entry.error.onSome { add("    Error: $it") }
        if (entry.metadata.isNotEmpty()) {
          add("    Metadata: ${formatValuePlain(entry.metadata)}")
        }
        entry.executionTrace.onSome { trace ->
          trace.tree.lines().forEach { line -> add("    $line") }
        }
      }

      // Snapshots
      snapshots.forEach { snapshot ->
        add("┌─ ${snapshot.system.uppercase()} ${"─".repeat(40)}")
        snapshot.summary.lines().forEach { add("  $it") }
        snapshot.state.forEach { (key, value) ->
          add("    $key: ${formatValuePlain(value)}")
          if (value is Collection<*>) {
            value.forEachIndexed { index, item ->
              when (item) {
                is Map<*, *> -> item.forEach { (k, v) -> add("        $k: $v") }
                else -> add("      [$index] ${formatValuePlain(item)}")
              }
            }
          }
        }
      }
    }

  /**
   * Find the longest word across all content lines.
   */
  private fun findLongestWord(lines: List<String>): Int =
    lines
      .flatMap { stripAnsi(it).split(Regex("\\s+")) }
      .maxOfOrNull { it.length } ?: 0

  // ══════════════════════════════════════════════════════════════════════════════
  // MAIN RENDER
  // ══════════════════════════════════════════════════════════════════════════════

  override fun render(report: TestReport, snapshots: List<SystemSnapshot>): String {
    val hasFailures = report.hasFailures()
    val borderColor = if (hasFailures) Colors.RED else Colors.CYAN
    val dimensions = calculateBoxDimensions(report, snapshots)

    return buildReport(report, snapshots, dimensions, borderColor, hasFailures)
  }

  private fun buildReport(
    report: TestReport,
    snapshots: List<SystemSnapshot>,
    dim: BoxDimensions,
    borderColor: String,
    hasFailures: Boolean
  ): String = listOf(
    renderHeader(report, dim, borderColor, hasFailures),
    renderTimeline(report, dim),
    renderSnapshots(snapshots, dim),
    emptyLine(dim.boxWidth),
    bottomBorder(borderColor, dim.boxWidth)
  ).joinToString("\n")

  private fun renderHeader(report: TestReport, dim: BoxDimensions, borderColor: String, hasFailures: Boolean): String {
    val statusText = if (hasFailures) {
      colorize("FAILED", Colors.BOLD + Colors.BRIGHT_RED)
    } else {
      colorize("IN PROGRESS", Colors.BRIGHT_BLUE)
    }

    return listOf(
      topBorder(borderColor, dim.boxWidth),
      centeredLine("STOVE TEST EXECUTION REPORT", Colors.BOLD + Colors.WHITE, dim),
      emptyLine(dim.boxWidth),
      contentLine("Test: ${colorize(report.testName, Colors.BRIGHT_YELLOW)}", dim),
      contentLine("ID: ${colorize(report.testId, Colors.DIM)}", dim),
      contentLine("Status: $statusText", dim)
    ).joinToString("\n")
  }

  private fun renderTimeline(report: TestReport, dim: BoxDimensions): String = listOf(
    divider(Colors.CYAN, dim.boxWidth),
    emptyLine(dim.boxWidth),
    contentLine(colorize("TIMELINE", Colors.BOLD + Colors.WHITE), dim),
    contentLine(colorize("─".repeat(8), Colors.DIM), dim),
    emptyLine(dim.boxWidth),
    report.entries().flatMap { renderEntry(it, dim) }.joinToString("\n")
  ).joinToString("\n")

  // ══════════════════════════════════════════════════════════════════════════════
  // BOX STRUCTURE
  // ══════════════════════════════════════════════════════════════════════════════

  private fun topBorder(color: String, boxWidth: Int): String =
    "$color╔${"═".repeat(boxWidth - 2)}╗${Colors.RESET}"

  private fun bottomBorder(color: String, boxWidth: Int): String =
    "$color╚${"═".repeat(boxWidth - 2)}╝${Colors.RESET}"

  private fun divider(color: String, boxWidth: Int): String =
    "$color╠${"═".repeat(boxWidth - 2)}╣${Colors.RESET}"

  private fun emptyLine(boxWidth: Int): String =
    "${Colors.CYAN}║${Colors.RESET}${" ".repeat(boxWidth - 2)}${Colors.CYAN}║${Colors.RESET}"

  // ══════════════════════════════════════════════════════════════════════════════
  // CONTENT LINES
  // ══════════════════════════════════════════════════════════════════════════════

  private fun centeredLine(text: String, color: String, dim: BoxDimensions): String {
    val plainText = stripAnsi(text)
    val leftPadding = ((dim.contentWidth - plainText.length) / 2).coerceAtLeast(0)
    val rightPadding = (dim.contentWidth - leftPadding - plainText.length).coerceAtLeast(0)
    val coloredText = if (color.isNotEmpty()) colorize(plainText, color) else text
    return "${Colors.CYAN}║${Colors.RESET} ${" ".repeat(leftPadding)}$coloredText${" ".repeat(rightPadding)} ${Colors.CYAN}║${Colors.RESET}"
  }

  private fun contentLine(text: String, dim: BoxDimensions, indent: Int = 0): String {
    val indentStr = " ".repeat(indent)
    val availableWidth = dim.contentWidth - indent

    return text
      .split('\n')
      .flatMap { wrapText(it.replace("\r", ""), availableWidth) }
      .joinToString("\n") { formatContentLine(it, indentStr, availableWidth) }
  }

  private fun formatContentLine(line: String, indentStr: String, availableWidth: Int): String {
    val padding = (availableWidth - stripAnsi(line).length).coerceAtLeast(0)
    return "${Colors.CYAN}║${Colors.RESET} $indentStr$line${" ".repeat(padding)} ${Colors.CYAN}║${Colors.RESET}"
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // ENTRY RENDERING
  // ══════════════════════════════════════════════════════════════════════════════

  private fun renderEntry(entry: ReportEntry, dim: BoxDimensions): List<String> {
    val headerLine = renderEntryHeader(entry, dim)
    val detailLines = renderEntryDetails(entry, dim)
    val failureLines = if (entry.isFailed) renderFailureDetails(entry, dim) else emptyList()
    val traceLines = entry.executionTrace.fold({ emptyList() }) { renderTraceVisualization(it, dim) }

    return listOf(headerLine) + detailLines + failureLines + traceLines + emptyLine(dim.boxWidth)
  }

  private fun renderEntryHeader(entry: ReportEntry, dim: BoxDimensions): String {
    val time = colorize(entry.timestamp.atZone(ZoneId.systemDefault()).format(timeFormatter), Colors.DIM)
    val (icon, status) = when (entry.result) {
      AssertionResult.PASSED -> colorize("✓", Colors.BRIGHT_GREEN) to colorize("PASSED", Colors.BRIGHT_GREEN)
      AssertionResult.FAILED -> colorize("✗", Colors.BRIGHT_RED) to colorize("FAILED", Colors.BRIGHT_RED)
    }
    val system = colorize("[${entry.system}]", Colors.BRIGHT_CYAN)
    return contentLine("$time $icon $status $system ${entry.action}", dim)
  }

  private fun renderEntryDetails(entry: ReportEntry, dim: BoxDimensions): List<String> = buildList {
    entry.input.onSome { add(contentLine("${colorize("Input:", Colors.YELLOW)} ${formatValue(it)}", dim, indent = 4)) }
    entry.output.onSome { add(contentLine("${colorize("Output:", Colors.YELLOW)} ${formatValue(it)}", dim, indent = 4)) }
    if (entry.metadata.isNotEmpty()) {
      add(contentLine("${colorize("Metadata:", Colors.DIM)} ${formatValue(entry.metadata)}", dim, indent = 4))
    }
  }

  private fun renderFailureDetails(entry: ReportEntry, dim: BoxDimensions): List<String> = buildList {
    entry.expected.onSome { addAll(renderLabeledContent("Expected:", formatValue(it), Colors.GREEN, dim, indent = 4)) }
    entry.actual.onSome { addAll(renderLabeledContent("Actual:", formatValue(it), Colors.RED, dim, indent = 4)) }
    entry.error.onSome { addAll(renderLabeledContent("Error:", it, Colors.BRIGHT_RED, dim, indent = 4)) }
  }

  private fun renderLabeledContent(
    label: String,
    content: String,
    labelColor: String,
    dim: BoxDimensions,
    indent: Int
  ): List<String> {
    val coloredLabel = colorize(label, labelColor)
    val lines = content.split('\n').map { it.replace("\r", "").trim() }.filter { it.isNotEmpty() }

    return if (lines.size == 1 && stripAnsi("$label $content").length <= dim.contentWidth - indent) {
      listOf(contentLine("$coloredLabel $content", dim, indent = indent))
    } else {
      listOf(contentLine(coloredLabel, dim, indent = indent)) +
        lines.map { contentLine(it, dim, indent = indent + 2) }
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // TRACE VISUALIZATION
  // ══════════════════════════════════════════════════════════════════════════════

  private fun renderTraceVisualization(trace: TraceVisualization, dim: BoxDimensions): List<String> {
    if (trace.totalSpans == 0) return emptyList()

    val header = colorize("Execution Trace:", Colors.BOLD + Colors.CYAN)
    val traceIdInfo = colorize("TraceId: ${trace.traceId}", Colors.DIM)
    val failedInfo = if (trace.failedSpans > 0) ", ${colorize("Failed: ${trace.failedSpans}", Colors.BRIGHT_RED)}" else ""
    val summary = colorize("Total spans: ${trace.totalSpans}$failedInfo", Colors.YELLOW)

    val treeLines = trace.tree.lines().map { line ->
      val coloredLine = when {
        line.contains("✗") -> colorize(line, Colors.BRIGHT_RED)
        line.contains("✓") -> colorize(line, Colors.GREEN)
        else -> colorize(line, Colors.WHITE)
      }
      contentLine(coloredLine, dim, indent = 4)
    }

    return listOf(
      emptyLine(dim.boxWidth),
      contentLine(header, dim, indent = 4),
      contentLine(traceIdInfo, dim, indent = 6),
      contentLine(summary, dim, indent = 6),
      emptyLine(dim.boxWidth)
    ) + treeLines
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // SNAPSHOT RENDERING
  // ══════════════════════════════════════════════════════════════════════════════

  private fun renderSnapshots(snapshots: List<SystemSnapshot>, dim: BoxDimensions): String =
    if (snapshots.isEmpty()) {
      ""
    } else {
      listOf(
        divider(Colors.CYAN, dim.boxWidth),
        emptyLine(dim.boxWidth),
        contentLine(colorize("SYSTEM SNAPSHOTS", Colors.BOLD + Colors.WHITE), dim),
        contentLine(colorize("─".repeat(16), Colors.DIM), dim),
        emptyLine(dim.boxWidth),
        snapshots.flatMap { renderSnapshot(it, dim) }.joinToString("\n")
      ).joinToString("\n")
    }

  private fun renderSnapshot(snapshot: SystemSnapshot, dim: BoxDimensions): List<String> {
    val header = "┌─ ${snapshot.system.uppercase()} "
    val headerLine = header + "─".repeat((dim.contentWidth - header.length).coerceAtLeast(0))

    val summaryLines = snapshot.summary
      .lines()
      .filter { it.isNotBlank() }
      .map { contentLine(colorize(it, Colors.WHITE), dim, indent = 2) }

    val stateLines = if (snapshot.state.isNotEmpty()) {
      listOf(
        emptyLine(dim.boxWidth),
        contentLine(colorize("State Details:", Colors.BOLD + Colors.WHITE), dim, indent = 2)
      ) + renderSnapshotState(snapshot.state, dim, indent = 4)
    } else {
      emptyList()
    }

    return listOf(
      contentLine(colorize(headerLine, Colors.MAGENTA), dim),
      emptyLine(dim.boxWidth)
    ) + summaryLines + stateLines + emptyLine(dim.boxWidth)
  }

  private fun renderSnapshotState(state: Map<String, Any>, dim: BoxDimensions, indent: Int): List<String> =
    state.flatMap { (key, value) ->
      val coloredKey = colorize(key, Colors.YELLOW)
      when (value) {
        is Collection<*> -> {
          listOf(contentLine("$coloredKey: ${colorize("${value.size} item(s)", Colors.DIM)}", dim, indent = indent)) +
            value.flatMapIndexed { index, item -> renderSnapshotItem(index, item, dim, indent + 2) }
        }

        else -> {
          listOf(contentLine("$coloredKey: ${formatValue(value)}", dim, indent = indent))
        }
      }
    }

  private fun renderSnapshotItem(index: Int, item: Any?, dim: BoxDimensions, indent: Int): List<String> {
    val indexLabel = colorize("[$index]", Colors.DIM)
    return when (item) {
      is Map<*, *> -> {
        listOf(contentLine(indexLabel, dim, indent = indent)) +
          item.map { (k, v) -> contentLine("${colorize(k.toString(), Colors.CYAN)}: $v", dim, indent = indent + 2) }
      }

      else -> {
        listOf(contentLine("$indexLabel ${formatValue(item)}", dim, indent = indent))
      }
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // TEXT WRAPPING (Smart word-boundary wrapping)
  // ══════════════════════════════════════════════════════════════════════════════

  /**
   * Wrap text at word boundaries. Never breaks mid-word.
   * If text fits within maxWidth, returns it as-is.
   * If text needs wrapping, breaks only at spaces.
   */
  private fun wrapText(text: String, maxWidth: Int): List<String> {
    val plainText = stripAnsi(text)
    return when {
      maxWidth <= 0 -> listOf(text)
      plainText.length <= maxWidth -> listOf(text)
      else -> wrapTextRecursive(text, plainText, maxWidth, emptyList(), 0)
    }
  }

  /**
   * Recursive word wrapping that preserves ANSI codes.
   * Breaks only at spaces - never mid-word.
   */
  private tailrec fun wrapTextRecursive(
    remaining: String,
    plainRemaining: String,
    maxWidth: Int,
    accumulated: List<String>,
    iterations: Int
  ): List<String> = when {
    // Safety limit to prevent infinite loops
    iterations >= 100 -> {
      accumulated.ifEmpty { listOf(remaining) }
    }

    // No more text to process
    plainRemaining.isEmpty() -> {
      accumulated.ifEmpty { listOf(remaining) }
    }

    // Remaining text fits - we're done
    plainRemaining.length <= maxWidth -> {
      accumulated + remaining
    }

    else -> {
      val breakPoint = findSpaceBreakPoint(plainRemaining, maxWidth)

      when (breakPoint) {
        // No space found - keep entire text on one line (single long word)
        null -> {
          accumulated + remaining
        }

        // Found a space - break there
        else -> {
          val originalBreakPoint = findOriginalPosition(remaining, breakPoint)
          val line = remaining.substring(0, originalBreakPoint).trimEnd()
          val rest = remaining.substring(originalBreakPoint).trimStart()
          wrapTextRecursive(rest, stripAnsi(rest), maxWidth, accumulated + line, iterations + 1)
        }
      }
    }
  }

  /**
   * Find a space character to break at, searching backwards from maxWidth.
   * Returns null if no space is found (never break mid-word).
   */
  private fun findSpaceBreakPoint(plainText: String, maxWidth: Int): Int? =
    (maxWidth downTo 1).firstOrNull { plainText.getOrNull(it) == ' ' }

  // ══════════════════════════════════════════════════════════════════════════════
  // ANSI CODE HANDLING
  // ══════════════════════════════════════════════════════════════════════════════

  /**
   * Find the position in the original string (with ANSI codes) that corresponds
   * to a position in the plain text (without ANSI codes).
   */
  private fun findOriginalPosition(original: String, plainPosition: Int): Int =
    findOriginalPositionRecursive(original, plainPosition, 0, 0, false)

  private tailrec fun findOriginalPositionRecursive(
    original: String,
    plainPosition: Int,
    originalIndex: Int,
    plainIndex: Int,
    inEscape: Boolean
  ): Int = when {
    originalIndex >= original.length || plainIndex >= plainPosition -> skipTrailingEscapes(original, originalIndex)
    original[originalIndex] == '\u001B' -> findOriginalPositionRecursive(original, plainPosition, originalIndex + 1, plainIndex, true)
    inEscape -> findOriginalPositionRecursive(original, plainPosition, originalIndex + 1, plainIndex, original[originalIndex] != 'm')
    else -> findOriginalPositionRecursive(original, plainPosition, originalIndex + 1, plainIndex + 1, false)
  }

  private tailrec fun skipTrailingEscapes(text: String, index: Int): Int = when {
    index >= text.length -> index
    text[index] != '\u001B' -> index
    else -> skipTrailingEscapes(text, skipToEndOfEscape(text, index))
  }

  private tailrec fun skipToEndOfEscape(text: String, index: Int): Int = when {
    index >= text.length -> index
    text[index] == 'm' -> index + 1
    else -> skipToEndOfEscape(text, index + 1)
  }

  /** Remove all ANSI escape codes from text. */
  private fun stripAnsi(text: String): String = text.replace(Regex("\u001B\\[[0-9;]*m"), "")

  /** Apply ANSI color codes to text. */
  private fun colorize(text: String, color: String): String = "$color$text${Colors.RESET}"

  // ══════════════════════════════════════════════════════════════════════════════
  // VALUE FORMATTING
  // ══════════════════════════════════════════════════════════════════════════════

  /** Format a value with ANSI colors for display. */
  private fun formatValue(obj: Any?): String = when (obj) {
    null -> colorize("none", Colors.DIM)
    is Option<*> -> obj.getOrElse { null }?.let { formatValue(it) } ?: colorize("none", Colors.DIM)
    is String -> obj
    is Number -> colorize(obj.toString(), Colors.BRIGHT_YELLOW)
    is Boolean -> colorize(obj.toString(), if (obj) Colors.GREEN else Colors.RED)
    is Collection<*> -> if (obj.isEmpty()) colorize("[]", Colors.DIM) else colorize("[${obj.size} items]", Colors.DIM)
    is Map<*, *> -> formatMapValue(obj)
    else -> obj.toString()
  }.replace("\n", " ").replace("\r", "")

  private fun formatMapValue(map: Map<*, *>): String =
    if (map.isEmpty()) {
      colorize("{}", Colors.DIM)
    } else {
      map.entries.joinToString(", ", "{", "}") { "${colorize(it.key.toString(), Colors.CYAN)}=${it.value}" }
    }

  /** Format a value without ANSI codes (for width calculation). */
  private fun formatValuePlain(obj: Any?): String = when (obj) {
    null -> "none"
    is Option<*> -> obj.getOrElse { null }?.let { formatValuePlain(it) } ?: "none"
    is String -> obj
    is Number, is Boolean -> obj.toString()
    is Collection<*> -> if (obj.isEmpty()) "[]" else "[${obj.size} items]"
    is Map<*, *> -> if (obj.isEmpty()) "{}" else obj.entries.joinToString(", ", "{", "}") { "${it.key}=${it.value}" }
    else -> obj.toString()
  }.replace("\n", " ").replace("\r", "")
}
