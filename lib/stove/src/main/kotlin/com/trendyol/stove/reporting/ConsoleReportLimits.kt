package com.trendyol.stove.reporting

/**
 * Limits applied by [PrettyConsoleRenderer.compact] and [PrettyConsoleRenderer.ciAware].
 *
 * Counts in the report always describe the complete test. Only the inline diagnostic detail is
 * shortened, with explicit notes showing what was omitted. [maxOutputCharacters] is a final safety
 * net that also applies after another component appends diagnostics such as an execution trace.
 */
data class ConsoleReportLimits(
  val maxTimelineEntries: Int = 50,
  val maxCollectionItems: Int = 10,
  val maxMapEntries: Int = 20,
  val maxSnapshots: Int = 10,
  val maxValueCharacters: Int = 2_000,
  val maxNestingDepth: Int = 8,
  val maxOutputCharacters: Int = 50_000
) {
  init {
    require(maxTimelineEntries > 0) { "maxTimelineEntries must be greater than zero" }
    require(maxCollectionItems > 0) { "maxCollectionItems must be greater than zero" }
    require(maxMapEntries > 0) { "maxMapEntries must be greater than zero" }
    require(maxSnapshots > 0) { "maxSnapshots must be greater than zero" }
    require(maxValueCharacters > 0) { "maxValueCharacters must be greater than zero" }
    require(maxNestingDepth > 0) { "maxNestingDepth must be greater than zero" }
    require(maxOutputCharacters >= MIN_OUTPUT_CHARACTERS) {
      "maxOutputCharacters must be at least $MIN_OUTPUT_CHARACTERS"
    }
  }
}

internal fun limitReportValue(
  value: String,
  maxCharacters: Int
): String {
  if (value.length <= maxCharacters) return value

  val headCharacters = maxCharacters * 3 / 4
  val tailCharacters = maxCharacters - headCharacters
  val omittedCharacters = value.length - maxCharacters
  return buildString {
    append(value.take(headCharacters))
    append("\n… $omittedCharacters character(s) omitted in compact output …\n")
    append(value.takeLast(tailCharacters))
  }
}

private const val MIN_OUTPUT_CHARACTERS = 256
