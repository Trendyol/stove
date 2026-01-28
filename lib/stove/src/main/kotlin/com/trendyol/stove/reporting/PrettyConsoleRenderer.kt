package com.trendyol.stove.reporting

import arrow.core.Option
import arrow.core.getOrElse
import com.trendyol.stove.tracing.TraceVisualization
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pretty console renderer for human-readable test reports.
 * Uses box drawing characters, colors, and clear formatting for easy reading.
 * Automatically wraps long lines to maintain the box frame.
 *
 * Implemented with pure functional programming - no mutation, no vars, no side effects.
 */
@Suppress("MagicNumber", "TooManyFunctions")
object PrettyConsoleRenderer : ReportRenderer {
  private const val BOX_WIDTH = 100
  private const val CONTENT_WIDTH = BOX_WIDTH - 4
  private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

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

  override fun render(report: TestReport, snapshots: List<SystemSnapshot>): String {
    val hasFailures = report.hasFailures()
    val borderColor = if (hasFailures) Colors.RED else Colors.CYAN

    return listOf(
      topBorder(borderColor),
      centeredLine("STOVE TEST EXECUTION REPORT", Colors.BOLD + Colors.WHITE),
      emptyLine(),
      contentLine("Test: ${colorize(report.testName, Colors.BRIGHT_YELLOW)}"),
      contentLine("ID: ${colorize(report.testId, Colors.DIM)}"),
      contentLine(
        "Status: ${
          if (hasFailures) {
            colorize("FAILED", Colors.BOLD + Colors.BRIGHT_RED)
          } else {
            colorize("IN PROGRESS", Colors.BRIGHT_BLUE)
          }
        }"
      ),
      divider(borderColor),
      emptyLine(),
      contentLine(colorize("TIMELINE", Colors.BOLD + Colors.WHITE)),
      contentLine(colorize("─".repeat(8), Colors.DIM)),
      emptyLine(),
      report.entries().flatMap(::renderEntry).joinToString("\n"),
      renderSnapshots(snapshots),
      emptyLine(),
      bottomBorder(borderColor)
    ).joinToString("\n")
  }

  private fun colorize(text: String, color: String): String = "$color$text${Colors.RESET}"

  private fun topBorder(color: String): String =
    "$color╔${"═".repeat(BOX_WIDTH - 2)}╗${Colors.RESET}"

  private fun bottomBorder(color: String): String =
    "$color╚${"═".repeat(BOX_WIDTH - 2)}╝${Colors.RESET}"

  private fun divider(color: String): String =
    "$color╠${"═".repeat(BOX_WIDTH - 2)}╣${Colors.RESET}"

  private fun emptyLine(): String =
    "${Colors.CYAN}║${Colors.RESET}${" ".repeat(BOX_WIDTH - 2)}${Colors.CYAN}║${Colors.RESET}"

  private fun centeredLine(text: String, color: String = ""): String {
    val plainText = stripAnsi(text)
    val truncated = if (plainText.length > CONTENT_WIDTH) plainText.take(CONTENT_WIDTH) else text
    val plainTruncated = stripAnsi(truncated)
    val padding = (CONTENT_WIDTH - plainTruncated.length) / 2
    val rightPadding = CONTENT_WIDTH - padding - plainTruncated.length
    val coloredText = if (color.isNotEmpty()) colorize(plainTruncated, color) else truncated
    return "${Colors.CYAN}║${Colors.RESET} ${" ".repeat(padding)}$coloredText${" ".repeat(rightPadding)} ${Colors.CYAN}║${Colors.RESET}"
  }

  private fun contentLine(text: String, indent: Int = 0): String {
    val indentStr = " ".repeat(indent)
    val availableWidth = CONTENT_WIDTH - indent

    return text
      .split('\n')
      .flatMap { rawLine -> wrapText(rawLine.replace("\r", ""), availableWidth) }
      .joinToString("\n") { wrappedLine -> formatContentLine(wrappedLine, indentStr, availableWidth) }
  }

  private fun formatContentLine(wrappedLine: String, indentStr: String, availableWidth: Int): String {
    val plainLine = stripAnsi(wrappedLine)
    val (finalLine, finalPlainLength) = if (plainLine.length > availableWidth) {
      truncateWithAnsi(wrappedLine, availableWidth - 3) + "..." to availableWidth
    } else {
      wrappedLine to plainLine.length
    }
    val padding = (availableWidth - finalPlainLength).coerceAtLeast(0)
    return "${Colors.CYAN}║${Colors.RESET} $indentStr$finalLine${" ".repeat(padding)} ${Colors.CYAN}║${Colors.RESET}"
  }

  private fun truncateWithAnsi(text: String, targetLength: Int): String =
    if (targetLength <= 0) "" else truncateWithAnsiRec(text, targetLength, 0, 0, "")

  private tailrec fun truncateWithAnsiRec(
    text: String,
    targetLength: Int,
    textIndex: Int,
    plainLength: Int,
    acc: String
  ): String = when {
    textIndex >= text.length || plainLength >= targetLength -> {
      acc + Colors.RESET
    }

    text[textIndex] == '\u001B' -> {
      val (escapeSeq, newIndex) = extractEscapeSequence(text, textIndex)
      truncateWithAnsiRec(text, targetLength, newIndex, plainLength, acc + escapeSeq)
    }

    else -> {
      truncateWithAnsiRec(text, targetLength, textIndex + 1, plainLength + 1, acc + text[textIndex])
    }
  }

  private fun extractEscapeSequence(text: String, startIndex: Int): Pair<String, Int> =
    extractEscapeSequenceRec(text, startIndex, "")

  private tailrec fun extractEscapeSequenceRec(text: String, index: Int, acc: String): Pair<String, Int> = when {
    index >= text.length -> acc to index
    text[index] == 'm' -> (acc + 'm') to (index + 1)
    else -> extractEscapeSequenceRec(text, index + 1, acc + text[index])
  }

  private fun renderEntry(entry: ReportEntry): List<String> {
    val formattedTime = colorize(
      entry.timestamp.atZone(ZoneId.systemDefault()).format(timeFormatter),
      Colors.DIM
    )

    val (icon, statusText) = when (entry.result) {
      AssertionResult.PASSED -> colorize("✓", Colors.BRIGHT_GREEN) to colorize("PASSED", Colors.BRIGHT_GREEN)
      AssertionResult.FAILED -> colorize("✗", Colors.BRIGHT_RED) to colorize("FAILED", Colors.BRIGHT_RED)
    }

    val system = colorize("[${entry.system}]", Colors.BRIGHT_CYAN)
    val headerLine = contentLine("$formattedTime $icon $statusText $system ${entry.action}")

    val inputLine = entry.input.fold({
      emptyList()
    }) { listOf(contentLine("${colorize("Input:", Colors.YELLOW)} ${formatValue(it)}", indent = 4)) }
    val outputLine = entry.output.fold({
      emptyList()
    }) { listOf(contentLine("${colorize("Output:", Colors.YELLOW)} ${formatValue(it)}", indent = 4)) }
    val metadataLine = if (entry.metadata.isNotEmpty()) {
      listOf(contentLine("${colorize("Metadata:", Colors.DIM)} ${formatValue(entry.metadata)}", indent = 4))
    } else {
      emptyList()
    }

    val failureLines = if (entry.isFailed) {
      listOf(
        entry.expected.fold({ emptyList() }) { renderLabeledContent("Expected:", formatValue(it), Colors.GREEN, indent = 4) },
        entry.actual.fold({ emptyList() }) { renderLabeledContent("Actual:", formatValue(it), Colors.RED, indent = 4) },
        entry.error.fold({ emptyList() }) { renderLabeledContent("Error:", it, Colors.BRIGHT_RED, indent = 4) }
      ).flatten()
    } else {
      emptyList()
    }

    // Render trace information if available
    val traceLines = entry.executionTrace.fold({
      emptyList()
    }) { trace ->
      renderTraceVisualization(trace)
    }

    return listOf(headerLine) + inputLine + outputLine + metadataLine + failureLines + traceLines + emptyLine()
  }

  private fun renderTraceVisualization(trace: TraceVisualization): List<String> {
    if (trace.totalSpans == 0) {
      return emptyList()
    }

    val header = colorize("Execution Trace:", Colors.BOLD + Colors.CYAN)
    val traceIdInfo = colorize("TraceId: ${trace.traceId}", Colors.DIM)
    val summary = "Total spans: ${trace.totalSpans}" +
      if (trace.failedSpans > 0) ", ${colorize("Failed: ${trace.failedSpans}", Colors.BRIGHT_RED)}" else ""

    val treeLines = trace.tree.lines().map { line ->
      // Colorize the tree based on status icons
      val coloredLine = when {
        line.contains("✗") -> colorize(line, Colors.BRIGHT_RED)
        line.contains("✓") -> colorize(line, Colors.GREEN)
        else -> colorize(line, Colors.WHITE)
      }
      contentLine(coloredLine, indent = 4)
    }

    return listOf(
      emptyLine(),
      contentLine(header, indent = 4),
      contentLine(traceIdInfo, indent = 6),
      contentLine(colorize(summary, Colors.YELLOW), indent = 6),
      emptyLine()
    ) + treeLines
  }

  private fun renderLabeledContent(label: String, content: String, labelColor: String, indent: Int): List<String> {
    val coloredLabel = colorize(label, labelColor)
    val lines = content.split('\n').map { it.replace("\r", "").trim() }.filter { it.isNotEmpty() }

    return if (lines.size == 1 && stripAnsi("$label $content").length <= CONTENT_WIDTH - indent) {
      listOf(contentLine("$coloredLabel $content", indent = indent))
    } else {
      listOf(contentLine(coloredLabel, indent = indent)) +
        lines.map { line -> contentLine(line, indent = indent + 2) }
    }
  }

  private fun renderSnapshots(snapshots: List<SystemSnapshot>): String =
    if (snapshots.isEmpty()) {
      ""
    } else {
      listOf(
        divider(Colors.CYAN),
        emptyLine(),
        contentLine(colorize("SYSTEM SNAPSHOTS", Colors.BOLD + Colors.WHITE)),
        contentLine(colorize("─".repeat(16), Colors.DIM)),
        emptyLine(),
        snapshots.flatMap(::renderSnapshot).joinToString("\n")
      ).joinToString("\n")
    }

  private fun renderSnapshot(snapshot: SystemSnapshot): List<String> {
    val header = "┌─ ${snapshot.system.uppercase()} "
    val headerLine = header + "─".repeat((CONTENT_WIDTH - header.length).coerceAtLeast(0))

    val summaryLines = snapshot.summary
      .lines()
      .filter { it.isNotBlank() }
      .map { line -> contentLine(colorize(line, Colors.WHITE), indent = 2) }

    val stateLines = if (snapshot.state.isNotEmpty()) {
      listOf(
        emptyLine(),
        contentLine(colorize("State Details:", Colors.BOLD + Colors.WHITE), indent = 2)
      ) + renderSnapshotState(snapshot.state, indent = 4)
    } else {
      emptyList()
    }

    return listOf(contentLine(colorize(headerLine, Colors.MAGENTA)), emptyLine()) +
      summaryLines + stateLines + emptyLine()
  }

  private fun renderSnapshotState(state: Map<String, Any>, indent: Int): List<String> =
    state.flatMap { (key, value) ->
      val coloredKey = colorize(key, Colors.YELLOW)
      when (value) {
        is Collection<*> -> {
          listOf(contentLine("$coloredKey: ${colorize("${value.size} item(s)", Colors.DIM)}", indent = indent)) +
            value.flatMapIndexed { index, item -> renderSnapshotItem(index, item, indent + 2) }
        }

        else -> {
          listOf(contentLine("$coloredKey: ${formatValue(value)}", indent = indent))
        }
      }
    }

  private fun renderSnapshotItem(index: Int, item: Any?, indent: Int): List<String> {
    val indexLabel = colorize("[$index]", Colors.DIM)
    return when (item) {
      is Map<*, *> -> {
        listOf(contentLine(indexLabel, indent = indent)) +
          item.map { (k, v) -> contentLine("${colorize(k.toString(), Colors.CYAN)}: $v", indent = indent + 2) }
      }

      else -> {
        listOf(contentLine("$indexLabel ${formatValue(item)}", indent = indent))
      }
    }
  }

  private fun wrapText(text: String, maxWidth: Int): List<String> {
    val plainText = stripAnsi(text)
    return when {
      maxWidth <= 0 -> listOf(text)
      plainText.length <= maxWidth -> listOf(text)
      else -> wrapTextRec(text, plainText, maxWidth, emptyList(), 0)
    }
  }

  private tailrec fun wrapTextRec(
    remaining: String,
    plainRemaining: String,
    maxWidth: Int,
    acc: List<String>,
    iterations: Int
  ): List<String> = when {
    iterations >= 100 -> {
      acc.ifEmpty { listOf(remaining) }
    }

    plainRemaining.isEmpty() -> {
      acc.ifEmpty { listOf(remaining) }
    }

    plainRemaining.length <= maxWidth -> {
      acc + remaining
    }

    else -> {
      val breakPoint = findBreakPoint(plainRemaining, maxWidth)
      val originalBreakPoint = findOriginalPosition(remaining, breakPoint)

      when {
        originalBreakPoint == 0 -> {
          val forcedBreak = findOriginalPosition(remaining, maxWidth.coerceAtMost(plainRemaining.length))
          if (forcedBreak > 0) {
            val newLine = remaining.substring(0, forcedBreak)
            val newRemaining = remaining.substring(forcedBreak)
            wrapTextRec(newRemaining, stripAnsi(newRemaining), maxWidth, acc + newLine, iterations + 1)
          } else {
            acc + remaining
          }
        }

        else -> {
          val newLine = remaining.substring(0, originalBreakPoint).trimEnd()
          val newRemaining = remaining.substring(originalBreakPoint).trimStart()
          wrapTextRec(newRemaining, stripAnsi(newRemaining), maxWidth, acc + newLine, iterations + 1)
        }
      }
    }
  }

  private fun findBreakPoint(plainText: String, maxWidth: Int): Int =
    (maxWidth downTo maxWidth / 2)
      .firstOrNull { i -> plainText.getOrNull(i) == ' ' }
      ?: maxWidth

  private fun findOriginalPosition(original: String, plainPosition: Int): Int =
    findOriginalPositionRec(original, plainPosition, 0, 0, false)

  private tailrec fun findOriginalPositionRec(
    original: String,
    plainPosition: Int,
    originalIndex: Int,
    plainIndex: Int,
    inEscape: Boolean
  ): Int = when {
    originalIndex >= original.length || plainIndex >= plainPosition -> {
      skipTrailingEscapes(original, originalIndex)
    }

    original[originalIndex] == '\u001B' -> {
      findOriginalPositionRec(original, plainPosition, originalIndex + 1, plainIndex, true)
    }

    inEscape -> {
      findOriginalPositionRec(original, plainPosition, originalIndex + 1, plainIndex, original[originalIndex] != 'm')
    }

    else -> {
      findOriginalPositionRec(original, plainPosition, originalIndex + 1, plainIndex + 1, false)
    }
  }

  private tailrec fun skipTrailingEscapes(text: String, index: Int): Int = when {
    index >= text.length -> {
      index
    }

    text[index] != '\u001B' -> {
      index
    }

    else -> {
      val afterEscape = skipToEndOfEscape(text, index)
      skipTrailingEscapes(text, afterEscape)
    }
  }

  private tailrec fun skipToEndOfEscape(text: String, index: Int): Int = when {
    index >= text.length -> index
    text[index] == 'm' -> index + 1
    else -> skipToEndOfEscape(text, index + 1)
  }

  private fun stripAnsi(text: String): String = text.replace(Regex("\u001B\\[[0-9;]*m"), "")

  private fun formatValue(obj: Any?): String = when (obj) {
    null -> colorize("none", Colors.DIM)

    is Option<*> -> obj.getOrElse { null }?.let { formatValue(it) } ?: colorize("none", Colors.DIM)

    is String -> obj

    is Number -> colorize(obj.toString(), Colors.BRIGHT_YELLOW)

    is Boolean -> colorize(obj.toString(), if (obj) Colors.GREEN else Colors.RED)

    is Collection<*> -> if (obj.isEmpty()) colorize("[]", Colors.DIM) else colorize("[${obj.size} items]", Colors.DIM)

    is Map<*, *> -> if (obj.isEmpty()) {
      colorize("{}", Colors.DIM)
    } else {
      obj.entries.joinToString(", ", "{", "}") { "${colorize(it.key.toString(), Colors.CYAN)}=${it.value}" }
    }

    else -> obj.toString()
  }.replace("\n", " ").replace("\r", "")
}
