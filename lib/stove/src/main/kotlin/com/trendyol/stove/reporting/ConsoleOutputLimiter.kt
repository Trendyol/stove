package com.trendyol.stove.reporting

internal fun limitReportOutput(
  output: String,
  maxCharacters: Int
): String {
  if (output.length <= maxCharacters) return output

  var notice = outputLimitNotice(output.length - maxCharacters)
  var head = ""
  var tail = ""

  repeat(2) {
    val retainedCharacters = (maxCharacters - notice.length).coerceAtLeast(0)
    val headCharacters = retainedCharacters * HEAD_PERCENT / PERCENT_BASE
    val tailCharacters = retainedCharacters - headCharacters
    head = output.takeAnsiSafePrefix(headCharacters)
    tail = output.takeAnsiSafeSuffix(tailCharacters)
    notice = outputLimitNotice(output.length - head.length - tail.length)
  }

  val retainedCharacters = (maxCharacters - notice.length).coerceAtLeast(0)
  val headCharacters = retainedCharacters * HEAD_PERCENT / PERCENT_BASE
  val tailCharacters = retainedCharacters - headCharacters
  head = output.takeAnsiSafePrefix(headCharacters)
  tail = output.takeAnsiSafeSuffix(tailCharacters)

  return head + notice + tail
}

private fun outputLimitNotice(omittedCharacters: Int): String =
  "$ANSI_RESET\n… $omittedCharacters character(s) omitted from compact report …\n$ANSI_RESET"

private fun String.takeAnsiSafePrefix(maxCharacters: Int): String {
  if (maxCharacters <= 0) return ""
  if (length <= maxCharacters) return this

  var endIndex = maxCharacters
  val lastEscape = lastIndexOf(ANSI_ESCAPE, endIndex - 1)
  val lastAnsiEnd = lastIndexOf(ANSI_END, endIndex - 1)
  if (lastEscape > lastAnsiEnd) endIndex = lastEscape
  return take(endIndex)
}

private fun String.takeAnsiSafeSuffix(maxCharacters: Int): String {
  if (maxCharacters <= 0) return ""
  if (length <= maxCharacters) return this

  var startIndex = length - maxCharacters
  val openEscape = lastIndexOf(ANSI_ESCAPE, startIndex)
  val closingIndex = indexOf(ANSI_END, startIndex)
  if (openEscape >= 0 && closingIndex >= startIndex && openEscape > lastIndexOf(ANSI_END, startIndex)) {
    startIndex = closingIndex + 1
  }
  return substring(startIndex)
}

private const val HEAD_PERCENT = 75
private const val PERCENT_BASE = 100
private const val ANSI_ESCAPE = '\u001B'
private const val ANSI_END = 'm'
private const val ANSI_RESET = "\u001B[0m"
