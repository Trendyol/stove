package com.trendyol.stove.reporting

internal object ConsoleTextWrapper {
  fun wrap(text: String, width: Int): String =
    text.lines().flatMap { wrapLine(it, width) }.joinToString("\n")

  fun visibleLength(value: String): Int = stripAnsi(value).length

  private fun wrapLine(line: String, width: Int): List<String> {
    if (line.isEmpty() || visibleLength(line) <= width) return listOf(line)

    val continuationIndent = buildContinuationIndent(stripAnsi(line))
    val wrapped = mutableListOf<String>()
    var remaining = line
    var remainingWidth = width

    while (visibleLength(remaining) > remainingWidth) {
      val breakAt = findWrapPosition(stripAnsi(remaining), remainingWidth)
      val rawBreakAt = rawIndexForVisibleIndex(remaining, breakAt)
      wrapped += remaining.substring(0, rawBreakAt).trimEnd()

      val nextRawStart = rawIndexAfterLeadingWhitespace(remaining, rawBreakAt)
      remaining = " ".repeat(continuationIndent) + remaining.substring(nextRawStart)
      remainingWidth = (width - continuationIndent).coerceAtLeast(MIN_CONTINUATION_WIDTH)
      if (visibleLength(remaining) <= width) {
        remainingWidth = width
      }
    }

    wrapped += remaining
    return wrapped
  }

  private fun buildContinuationIndent(line: String): Int {
    val leadingSpaces = line.takeWhile { it == ' ' }.length
    val content = line.drop(leadingSpaces)
    val labelIndex = content.indexOf(": ")
    return if (labelIndex in 1..LABEL_WRAP_INDENT_LIMIT) {
      leadingSpaces + labelIndex + 2
    } else {
      leadingSpaces + DETAIL_INDENT_STEP
    }
  }

  private fun findWrapPosition(line: String, width: Int): Int {
    val softBreakStart = (width - MAX_BREAK_SEARCH_WINDOW).coerceAtLeast(1)
    for (index in width downTo softBreakStart) {
      val previous = line.getOrNull(index - 1)
      val current = line.getOrNull(index)
      if (previous != null && isWrapDelimiter(previous)) return index
      if (current != null && current.isWhitespace()) return index
    }
    return width.coerceAtMost(line.length)
  }

  private fun isWrapDelimiter(char: Char): Boolean =
    char.isWhitespace() || char in WRAP_DELIMITERS

  private fun rawIndexForVisibleIndex(line: String, visibleIndex: Int): Int {
    var rawIndex = 0
    var visibleCount = 0
    while (rawIndex < line.length && visibleCount < visibleIndex) {
      if (line[rawIndex] == ANSI_ESCAPE) {
        rawIndex = advancePastAnsi(line, rawIndex)
      } else {
        rawIndex++
        visibleCount++
      }
    }
    return rawIndex
  }

  private fun rawIndexAfterLeadingWhitespace(line: String, startIndex: Int): Int {
    var rawIndex = startIndex
    while (rawIndex < line.length) {
      if (line[rawIndex] == ANSI_ESCAPE) {
        rawIndex = advancePastAnsi(line, rawIndex)
      } else if (line[rawIndex].isWhitespace()) {
        rawIndex++
      } else {
        break
      }
    }
    return rawIndex
  }

  private fun advancePastAnsi(line: String, startIndex: Int): Int {
    var rawIndex = startIndex + 1
    while (rawIndex < line.length && line[rawIndex] != ANSI_END) rawIndex++
    return (rawIndex + 1).coerceAtMost(line.length)
  }

  private fun stripAnsi(value: String): String = value.replace(ANSI_REGEX, "")

  private const val DETAIL_INDENT_STEP = 2
  private const val LABEL_WRAP_INDENT_LIMIT = 32
  private const val MIN_CONTINUATION_WIDTH = 12
  private const val MAX_BREAK_SEARCH_WINDOW = 12
  private const val ANSI_ESCAPE = '\u001B'
  private const val ANSI_END = 'm'
  private val WRAP_DELIMITERS = charArrayOf(',', ';', ')', ']', '}', '/', '_')
  private val ANSI_REGEX = Regex("\u001B\\[[0-9;]*m")
}
