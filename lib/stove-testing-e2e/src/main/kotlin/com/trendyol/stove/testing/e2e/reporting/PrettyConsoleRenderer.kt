package com.trendyol.stove.testing.e2e.reporting

import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pretty console renderer for human-readable test reports.
 * Uses box drawing characters, colors, and clear formatting for easy reading.
 * Automatically wraps long lines to maintain the box frame.
 */
@Suppress("MagicNumber", "TooManyFunctions")
object PrettyConsoleRenderer : ReportRenderer {
  private const val BOX_WIDTH = 100
  private const val CONTENT_WIDTH = BOX_WIDTH - 4 // Account for "║ " and " ║"
  private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

  // ANSI Color codes
  private object Colors {
    const val RESET = "\u001B[0m"
    const val BOLD = "\u001B[1m"
    const val DIM = "\u001B[2m"

    // Foreground colors
    const val RED = "\u001B[31m"
    const val GREEN = "\u001B[32m"
    const val YELLOW = "\u001B[33m"
    const val BLUE = "\u001B[34m"
    const val MAGENTA = "\u001B[35m"
    const val CYAN = "\u001B[36m"
    const val WHITE = "\u001B[37m"

    // Bright foreground colors
    const val BRIGHT_RED = "\u001B[91m"
    const val BRIGHT_GREEN = "\u001B[92m"
    const val BRIGHT_YELLOW = "\u001B[93m"
    const val BRIGHT_BLUE = "\u001B[94m"
    const val BRIGHT_CYAN = "\u001B[96m"
  }

  override fun render(report: TestReport, snapshots: List<SystemSnapshot>): String = buildString {
    val hasFailures = report.hasFailures()

    // Header
    topBorder(if (hasFailures) Colors.RED else Colors.CYAN)
    centeredLine("STOVE TEST EXECUTION REPORT", Colors.BOLD + Colors.WHITE)
    emptyLine()
    contentLine("Test: ${colorize(report.testName, Colors.BRIGHT_YELLOW)}")
    contentLine("ID: ${colorize(report.testId, Colors.DIM)}")

    if (hasFailures) {
      contentLine("Status: ${colorize("FAILED", Colors.BOLD + Colors.BRIGHT_RED)}")
    } else {
      contentLine("Status: ${colorize("IN PROGRESS", Colors.BRIGHT_BLUE)}")
    }

    divider(if (hasFailures) Colors.RED else Colors.CYAN)

    // Timeline
    emptyLine()
    contentLine(colorize("TIMELINE", Colors.BOLD + Colors.WHITE))
    contentLine(colorize("─".repeat(8), Colors.DIM))
    emptyLine()

    report.entries().forEach { entry ->
      when (entry) {
        is ActionEntry -> renderAction(entry)
        is AssertionEntry -> renderAssertion(entry)
      }
    }

    // Snapshots
    if (snapshots.isNotEmpty()) {
      divider(Colors.CYAN)
      emptyLine()
      contentLine(colorize("SYSTEM SNAPSHOTS", Colors.BOLD + Colors.WHITE))
      contentLine(colorize("─".repeat(16), Colors.DIM))
      emptyLine()

      snapshots.forEach { snapshot ->
        renderSnapshot(snapshot)
      }
    }

    // Footer
    emptyLine()
    bottomBorder(if (hasFailures) Colors.RED else Colors.CYAN)
  }

  private fun colorize(text: String, color: String): String = "$color$text${Colors.RESET}"

  private fun StringBuilder.topBorder(color: String = Colors.CYAN) {
    appendLine("$color╔${"═".repeat(BOX_WIDTH - 2)}╗${Colors.RESET}")
  }

  private fun StringBuilder.bottomBorder(color: String = Colors.CYAN) {
    appendLine("$color╚${"═".repeat(BOX_WIDTH - 2)}╝${Colors.RESET}")
  }

  private fun StringBuilder.divider(color: String = Colors.CYAN) {
    appendLine("$color╠${"═".repeat(BOX_WIDTH - 2)}╣${Colors.RESET}")
  }

  private fun StringBuilder.emptyLine() {
    appendLine("${Colors.CYAN}║${Colors.RESET}${" ".repeat(BOX_WIDTH - 2)}${Colors.CYAN}║${Colors.RESET}")
  }

  private fun StringBuilder.centeredLine(text: String, color: String = "") {
    val plainText = stripAnsi(text)
    val truncated = if (plainText.length > CONTENT_WIDTH) plainText.take(CONTENT_WIDTH) else text
    val plainTruncated = stripAnsi(truncated)
    val padding = (CONTENT_WIDTH - plainTruncated.length) / 2
    val rightPadding = CONTENT_WIDTH - padding - plainTruncated.length

    val coloredText = if (color.isNotEmpty()) colorize(plainTruncated, color) else truncated
    appendLine(
      "${Colors.CYAN}║${Colors.RESET} ${" ".repeat(padding)}$coloredText${" ".repeat(rightPadding)} ${Colors.CYAN}║${Colors.RESET}"
    )
  }

  private fun StringBuilder.contentLine(text: String, indent: Int = 0) {
    val indentStr = " ".repeat(indent)
    val availableWidth = CONTENT_WIDTH - indent

    // First split by actual newlines, then wrap each line
    text.split('\n').forEach { rawLine ->
      // Wrap each line to fit within the available width
      wrapText(rawLine.replace("\r", ""), availableWidth).forEach { wrappedLine ->
        val plainLine = stripAnsi(wrappedLine)

        // Safety: truncate if still too long (shouldn't happen, but ensures border integrity)
        val (finalLine, finalPlainLength) = if (plainLine.length > availableWidth) {
          val truncated = truncateWithAnsi(wrappedLine, availableWidth - 3) + "..."
          truncated to (availableWidth)
        } else {
          wrappedLine to plainLine.length
        }

        val padding = availableWidth - finalPlainLength
        appendLine(
          "${Colors.CYAN}║${Colors.RESET} $indentStr$finalLine${" ".repeat(padding.coerceAtLeast(0))} ${Colors.CYAN}║${Colors.RESET}"
        )
      }
    }
  }

  /**
   * Truncates a string with ANSI codes to a target plain-text length.
   */
  @Suppress("NestedBlockDepth")
  private fun truncateWithAnsi(text: String, targetLength: Int): String {
    if (targetLength <= 0) return ""
    val result = StringBuilder()
    var plainLength = 0
    var i = 0

    while (i < text.length && plainLength < targetLength) {
      if (text[i] == '\u001B') {
        // Copy entire ANSI sequence
        while (i < text.length) {
          result.append(text[i])
          if (text[i] == 'm') {
            i++
            break
          }
          i++
        }
      } else {
        result.append(text[i])
        plainLength++
        i++
      }
    }

    // Append reset code to ensure colors don't bleed
    result.append(Colors.RESET)
    return result.toString()
  }

  private fun StringBuilder.renderAction(entry: ActionEntry) {
    val formattedTime = colorize(
      entry.timestamp.atZone(ZoneId.systemDefault()).format(timeFormatter),
      Colors.DIM
    )

    // Determine icon and color based on result
    val (icon, statusText) = when (entry.result) {
      AssertionResult.PASSED -> colorize("✓", Colors.BRIGHT_GREEN) to colorize("PASSED", Colors.BRIGHT_GREEN)
      AssertionResult.FAILED -> colorize("✗", Colors.BRIGHT_RED) to colorize("FAILED", Colors.BRIGHT_RED)
      null -> colorize("●", Colors.BRIGHT_BLUE) to null
    }

    val system = colorize("[${entry.system}]", Colors.BRIGHT_CYAN)

    // Show status if present
    val statusPart = statusText?.let { " $it" } ?: ""
    contentLine("$formattedTime $icon$statusPart $system ${entry.action}")

    entry.input?.let {
      contentLine("${colorize("Input:", Colors.YELLOW)} ${formatValue(it)}", indent = 4)
    }
    entry.output?.let {
      contentLine("${colorize("Output:", Colors.YELLOW)} ${formatValue(it)}", indent = 4)
    }
    if (entry.metadata.isNotEmpty()) {
      contentLine("${colorize("Metadata:", Colors.DIM)} ${formatValue(entry.metadata)}", indent = 4)
    }

    // Show assertion details if failed
    if (entry.isFailed) {
      entry.expected?.let {
        renderLabeledContent("Expected:", formatValue(it), Colors.GREEN, indent = 4)
      }
      entry.actual?.let {
        renderLabeledContent("Actual:", formatValue(it), Colors.RED, indent = 4)
      }
      entry.error?.let {
        renderLabeledContent("Error:", it, Colors.BRIGHT_RED, indent = 4)
      }
    }

    emptyLine()
  }

  private fun StringBuilder.renderAssertion(entry: AssertionEntry) {
    val formattedTime = colorize(
      entry.timestamp.atZone(ZoneId.systemDefault()).format(timeFormatter),
      Colors.DIM
    )

    val (icon, statusColor) = if (entry.result == AssertionResult.PASSED) {
      colorize("✓", Colors.BRIGHT_GREEN) to Colors.BRIGHT_GREEN
    } else {
      colorize("✗", Colors.BRIGHT_RED) to Colors.BRIGHT_RED
    }

    val status = colorize(entry.result.name, Colors.BOLD + statusColor)
    val system = colorize("[${entry.system}]", Colors.BRIGHT_CYAN)

    contentLine("$formattedTime $icon $status $system ${entry.description}")

    if (entry.result == AssertionResult.FAILED) {
      entry.expected?.let {
        renderLabeledContent("Expected:", formatValue(it), Colors.GREEN, indent = 4)
      }
      entry.actual?.let {
        renderLabeledContent("Actual:", formatValue(it), Colors.RED, indent = 4)
      }
      entry.failure?.let {
        renderLabeledContent("Error:", it.message ?: "Unknown error", Colors.BRIGHT_RED, indent = 4)
      }
    }
    emptyLine()
  }

  /**
   * Renders a labeled content block where the label and content may be on separate lines
   * if the content is multi-line or too long.
   */
  private fun StringBuilder.renderLabeledContent(label: String, content: String, labelColor: String, indent: Int) {
    val coloredLabel = colorize(label, labelColor)
    val lines = content.split('\n').map { it.replace("\r", "").trim() }.filter { it.isNotEmpty() }

    if (lines.size == 1 && stripAnsi("$label $content").length <= CONTENT_WIDTH - indent) {
      // Single line that fits - render inline
      contentLine("$coloredLabel $content", indent = indent)
    } else {
      // Multi-line or long content - render label separately
      contentLine(coloredLabel, indent = indent)
      lines.forEach { line ->
        contentLine(line, indent = indent + 2)
      }
    }
  }

  private fun StringBuilder.renderSnapshot(snapshot: SystemSnapshot) {
    val header = "┌─ ${snapshot.system.uppercase()} "
    val headerLine = header + "─".repeat((CONTENT_WIDTH - header.length).coerceAtLeast(0))
    contentLine(colorize(headerLine, Colors.MAGENTA))
    emptyLine()

    // Render summary
    snapshot.summary.lines().forEach { line ->
      if (line.isNotBlank()) {
        contentLine(colorize(line, Colors.WHITE), indent = 2)
      }
    }

    // Render state if available
    if (snapshot.state.isNotEmpty()) {
      emptyLine()
      contentLine(colorize("State Details:", Colors.BOLD + Colors.WHITE), indent = 2)
      renderSnapshotState(snapshot.state, indent = 4)
    }

    emptyLine()
  }

  private fun StringBuilder.renderSnapshotState(state: Map<String, Any>, indent: Int) {
    state.forEach { (key, value) ->
      val coloredKey = colorize(key, Colors.YELLOW)
      when (value) {
        is Collection<*> -> {
          contentLine("$coloredKey: ${colorize("${value.size} item(s)", Colors.DIM)}", indent = indent)
          value.forEachIndexed { index, item ->
            renderSnapshotItem(index, item, indent + 2)
          }
        }

        else -> {
          contentLine("$coloredKey: ${formatValue(value)}", indent = indent)
        }
      }
    }
  }

  private fun StringBuilder.renderSnapshotItem(index: Int, item: Any?, indent: Int) {
    val indexLabel = colorize("[$index]", Colors.DIM)
    when (item) {
      is Map<*, *> -> {
        contentLine(indexLabel, indent = indent)
        item.forEach { (k, v) ->
          contentLine("${colorize(k.toString(), Colors.CYAN)}: $v", indent = indent + 2)
        }
      }

      else -> {
        contentLine("$indexLabel ${formatValue(item)}", indent = indent)
      }
    }
  }

  /**
   * Wraps text to fit within the specified width.
   * Tries to break at word boundaries when possible.
   * Accounts for ANSI escape codes in width calculation.
   */
  @Suppress("LoopWithTooManyJumpStatements")
  private fun wrapText(text: String, maxWidth: Int): List<String> {
    if (maxWidth <= 0) return listOf(text)

    val plainText = stripAnsi(text)
    if (plainText.length <= maxWidth) return listOf(text)

    val lines = mutableListOf<String>()
    var remaining = text
    var plainRemaining = plainText

    var iterations = 0
    val maxIterations = 100 // Safety limit

    while (plainRemaining.isNotEmpty() && iterations < maxIterations) {
      iterations++

      if (plainRemaining.length <= maxWidth) {
        lines.add(remaining)
        break
      }

      // Find the best break point in plain text
      var breakPoint = maxWidth

      // Try to find a space to break at (prefer word boundaries)
      var foundSpace = -1
      for (i in maxWidth downTo maxWidth / 2) {
        if (plainRemaining[i] == ' ') {
          foundSpace = i
          break
        }
      }

      if (foundSpace > 0) {
        breakPoint = foundSpace
      }

      // Find corresponding position in original text (with ANSI codes)
      val originalBreakPoint = findOriginalPosition(remaining, breakPoint)

      // Ensure we make progress
      if (originalBreakPoint == 0) {
        // Can't find a break point, force break at maxWidth
        val forcedBreak = findOriginalPosition(remaining, maxWidth.coerceAtMost(plainRemaining.length))
        if (forcedBreak > 0) {
          lines.add(remaining.substring(0, forcedBreak))
          remaining = remaining.substring(forcedBreak)
        } else {
          // Fallback: just add remaining and break
          lines.add(remaining)
          break
        }
      } else {
        lines.add(remaining.substring(0, originalBreakPoint).trimEnd())
        remaining = remaining.substring(originalBreakPoint).trimStart()
      }

      plainRemaining = stripAnsi(remaining)
    }

    return lines.ifEmpty { listOf(text) }
  }

  /**
   * Finds the position in the original string (with ANSI codes) that corresponds
   * to a position in the plain string (without ANSI codes).
   */
  private fun findOriginalPosition(original: String, plainPosition: Int): Int {
    var plainIndex = 0
    var originalIndex = 0
    var inEscape = false

    while (originalIndex < original.length && plainIndex < plainPosition) {
      val char = original[originalIndex]
      if (char == '\u001B') {
        inEscape = true
      } else if (inEscape) {
        if (char == 'm') {
          inEscape = false
        }
      } else {
        plainIndex++
      }
      originalIndex++
    }

    // Skip any trailing ANSI codes
    while (originalIndex < original.length && original[originalIndex] == '\u001B') {
      while (originalIndex < original.length && original[originalIndex] != 'm') {
        originalIndex++
      }
      if (originalIndex < original.length) originalIndex++ // skip 'm'
    }

    return originalIndex
  }

  /**
   * Strips ANSI escape codes from a string.
   */
  private fun stripAnsi(text: String): String = text.replace(Regex("\u001B\\[[0-9;]*m"), "")

  /**
   * Formats a value for display, handling different types appropriately.
   */
  private fun formatValue(obj: Any?): String {
    if (obj == null) return colorize("null", Colors.DIM)

    val str = when (obj) {
      is String -> {
        obj
      }

      is Number -> {
        colorize(obj.toString(), Colors.BRIGHT_YELLOW)
      }

      is Boolean -> {
        colorize(obj.toString(), if (obj) Colors.GREEN else Colors.RED)
      }

      is Collection<*> -> {
        if (obj.isEmpty()) {
          colorize("[]", Colors.DIM)
        } else {
          colorize("[${obj.size} items]", Colors.DIM)
        }
      }

      is Map<*, *> -> {
        if (obj.isEmpty()) {
          colorize("{}", Colors.DIM)
        } else {
          obj.entries.joinToString(", ", "{", "}") {
            "${colorize(it.key.toString(), Colors.CYAN)}=${it.value}"
          }
        }
      }

      else -> {
        obj.toString()
      }
    }

    // Clean up newlines for inline display, but don't truncate
    return str
      .replace("\n", " ")
      .replace("\r", "")
  }
}
