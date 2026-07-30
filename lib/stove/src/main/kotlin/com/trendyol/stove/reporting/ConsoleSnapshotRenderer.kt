package com.trendyol.stove.reporting

import com.github.ajalt.mordant.rendering.TextColors.brightCyan
import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.rendering.TextColors.brightRed
import com.github.ajalt.mordant.rendering.TextColors.brightYellow
import com.github.ajalt.mordant.rendering.TextColors.white
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim

internal class ConsoleSnapshotRenderer(
  private val policy: ConsoleReportPolicy
) {
  private val values = ConsoleValueRenderer(policy)

  fun render(snapshot: SystemSnapshot): String {
    val summaryLines = values
      .sanitize(snapshot.summary)
      .lines()
      .filter { it.isNotBlank() }
    val stateLines = renderState(snapshot.state)

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

  private fun renderState(state: Map<String, Any>): List<String> {
    val context = ConsoleRenderContext(policy.maxNestingDepth)
    return when (context.enter(state, depth = 0)) {
      ContainerVisit.CYCLE -> listOf(" ".repeat(INITIAL_STATE_INDENT) + "<CYCLE>")

      ContainerVisit.MAX_DEPTH -> listOf(" ".repeat(INITIAL_STATE_INDENT) + "<MAX DEPTH REACHED>")

      ContainerVisit.ENTERED ->
        try {
          renderMapEntries(
            value = state,
            indent = INITIAL_STATE_INDENT,
            childDepth = 1,
            context = context
          )
        } finally {
          context.leave(state)
        }
    }
  }

  private fun renderMapEntries(
    value: Map<*, *>,
    indent: Int,
    childDepth: Int,
    context: ConsoleRenderContext
  ): List<String> {
    val selected = policy.selectMapEntries(value)
    return buildList {
      selected.entries.forEach { (key, nestedValue) ->
        addAll(renderEntry(key.toString(), nestedValue, indent, childDepth, context))
      }
      if (selected.omittedEntries > 0) {
        val prefix = " ".repeat(indent)
        add("$prefix${dim("… ${selected.omittedEntries} map entry(s) omitted from compact output")}")
      }
    }
  }

  private fun renderEntry(
    key: String,
    value: Any?,
    indent: Int,
    depth: Int,
    context: ConsoleRenderContext
  ): List<String> {
    val prefix = " ".repeat(indent)
    val keyLabel = yellow(key)
    return when (value) {
      is Collection<*> -> renderContainer(value, "$prefix$keyLabel", depth, context) {
        renderCollectionEntry(key, keyLabel, value, indent, depth, context)
      }

      is Map<*, *> -> renderContainer(value, "$prefix$keyLabel", depth, context) {
        listOf("$prefix$keyLabel:") +
          renderMapEntries(
            value = value,
            indent = indent + SNAPSHOT_INDENT_STEP,
            childDepth = depth + 1,
            context = context
          )
      }

      else -> listOf("$prefix$keyLabel: ${styleValue(key, value)}")
    }
  }

  private fun renderCollectionEntry(
    key: String,
    keyLabel: String,
    value: Collection<*>,
    indent: Int,
    depth: Int,
    context: ConsoleRenderContext
  ): List<String> {
    val prefix = " ".repeat(indent)
    val count = "$prefix$keyLabel: ${styleCollectionCount(key, value.size)}"
    val selected = policy.selectCollectionItems(value)
    val itemIndent = " ".repeat(indent + SNAPSHOT_INDENT_STEP)
    val items = buildList {
      if (selected.omittedItems > 0) {
        add("$itemIndent${dim("… ${selected.omittedItems} earlier item(s) omitted in compact output")}")
      }
      selected.items.forEach {
        addAll(
          renderItem(
            index = it.index,
            item = it.value,
            indent = indent + SNAPSHOT_INDENT_STEP,
            depth = depth + 1,
            context = context
          )
        )
      }
    }
    return listOf(count) + items
  }

  private fun renderItem(
    index: Int,
    item: Any?,
    indent: Int,
    depth: Int,
    context: ConsoleRenderContext
  ): List<String> {
    val prefix = " ".repeat(indent)
    val indexLabel = dim("[$index]")
    return when (item) {
      is Map<*, *> -> renderContainer(item, "$prefix$indexLabel", depth, context) {
        listOf("$prefix$indexLabel") +
          renderMapEntries(
            value = item,
            indent = indent + SNAPSHOT_INDENT_STEP,
            childDepth = depth + 1,
            context = context
          )
      }

      is Collection<*> -> renderContainer(item, "$prefix$indexLabel", depth, context) {
        listOf("$prefix$indexLabel ${brightCyan("${item.size} item(s)")}")
      }

      else -> listOf("$prefix$indexLabel ${values.formatPlain(item)}")
    }
  }

  private inline fun renderContainer(
    value: Any,
    label: String,
    depth: Int,
    context: ConsoleRenderContext,
    render: () -> List<String>
  ): List<String> = when (context.enter(value, depth)) {
    ContainerVisit.CYCLE -> listOf("$label: <CYCLE>")

    ContainerVisit.MAX_DEPTH -> listOf("$label: <MAX DEPTH REACHED>")

    ContainerVisit.ENTERED ->
      try {
        render()
      } finally {
        context.leave(value)
      }
  }

  private fun styleSummaryLine(line: String): String {
    val lower = line.lowercase()
    val number = extractLastNumber(lower)
    return when {
      "failed" in lower -> if ((number ?: 0) == 0) brightGreen(line) else brightRed(line)
      "passed" in lower || "success" in lower -> brightGreen(line)
      ACTIVITY_LABELS.any { it in lower } -> brightCyan(line)
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

  private fun styleValue(key: String, value: Any?): String {
    val lower = key.lowercase()
    return when (value) {
      is Number -> when {
        "fail" in lower -> if (value.toInt() == 0) brightGreen(value.toString()) else brightRed(value.toString())
        "pass" in lower || "success" in lower -> brightGreen(value.toString())
        else -> brightYellow(value.toString())
      }

      is Boolean -> if (value) brightGreen("true") else brightRed("false")

      else -> values.formatPlain(value)
    }
  }

  private fun extractLastNumber(value: String): Int? =
    NUMBER_AT_END_REGEX
      .find(value)
      ?.groupValues
      ?.getOrNull(1)
      ?.toIntOrNull()

  private companion object {
    const val INITIAL_STATE_INDENT = 4
    const val SNAPSHOT_INDENT_STEP = 4
    val ACTIVITY_LABELS = listOf("consumed", "produced", "published", "registered", "served")
    val NUMBER_AT_END_REGEX = Regex("(\\d+)(?!.*\\d)")
  }
}
