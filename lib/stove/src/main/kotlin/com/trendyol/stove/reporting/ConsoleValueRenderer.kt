package com.trendyol.stove.reporting

import arrow.core.Option
import arrow.core.getOrElse
import com.github.ajalt.mordant.rendering.TextStyles.dim

internal class ConsoleValueRenderer(
  private val policy: ConsoleReportPolicy
) {
  fun sanitize(value: String): String {
    val normalized = value.replace("\r", "")
    return policy.limitValue(normalized)
  }

  fun renderDetailBlock(label: String, value: Any?): List<String> {
    val renderedValue = renderNestedValue(
      value = value,
      indent = 0,
      depth = 0,
      context = newRenderContext()
    )
    return if (renderedValue.size == 1) {
      listOf("$label: ${renderedValue.first().trimStart()}")
    } else {
      listOf("$label:") + renderedValue.map { "  $it" }
    }
  }

  fun formatPlain(value: Any?): String =
    formatPlain(value, depth = 0, context = newRenderContext())

  private fun renderNestedValue(
    value: Any?,
    indent: Int,
    depth: Int,
    context: ConsoleRenderContext
  ): List<String> {
    val prefix = " ".repeat(indent)
    return when (value) {
      null -> listOf("${prefix}none")

      is Option<*> -> renderNestedValue(value.getOrElse { null }, indent, depth, context)

      is String -> sanitize(value).lines().map { "$prefix$it" }

      is Number, is Boolean -> listOf("$prefix$value")

      is Map<*, *> -> renderContainer(value, prefix, depth, context) {
        renderNestedMap(value, indent, depth, context)
      }

      is Collection<*> -> renderContainer(value, prefix, depth, context) {
        renderNestedCollection(value, indent, depth, context)
      }

      else -> listOf("$prefix${sanitize(value.toString())}")
    }
  }

  private fun renderNestedMap(
    value: Map<*, *>,
    indent: Int,
    depth: Int,
    context: ConsoleRenderContext
  ): List<String> {
    val prefix = " ".repeat(indent)
    if (value.isEmpty()) return listOf("$prefix{}")

    val selected = policy.selectMapEntries(value)
    return buildList {
      selected.entries.forEach { (key, nestedValue) ->
        when (nestedValue) {
          is Map<*, *>, is Collection<*> -> {
            add("$prefix$key:")
            addAll(renderNestedValue(nestedValue, indent + DETAIL_INDENT_STEP, depth + 1, context))
          }

          else -> add("$prefix$key: ${formatPlain(nestedValue, depth + 1, context)}")
        }
      }
      if (selected.omittedEntries > 0) {
        add("$prefix${dim("… ${selected.omittedEntries} map entry(s) omitted from compact output")}")
      }
    }
  }

  private fun renderNestedCollection(
    value: Collection<*>,
    indent: Int,
    depth: Int,
    context: ConsoleRenderContext
  ): List<String> {
    val prefix = " ".repeat(indent)
    if (value.isEmpty()) return listOf("$prefix[]")

    val selected = policy.selectCollectionItems(value)
    return buildList {
      if (selected.omittedItems > 0) {
        add("$prefix${dim("… ${selected.omittedItems} earlier item(s) omitted in compact output")}")
      }
      selected.items.forEach { indexedItem ->
        when (val item = indexedItem.value) {
          is Map<*, *>, is Collection<*> -> {
            add("$prefix[${indexedItem.index}]")
            addAll(renderNestedValue(item, indent + DETAIL_INDENT_STEP, depth + 1, context))
          }

          else -> add("$prefix[${indexedItem.index}] ${formatPlain(item, depth + 1, context)}")
        }
      }
    }
  }

  private fun formatPlain(
    value: Any?,
    depth: Int,
    context: ConsoleRenderContext
  ): String = when (value) {
    null -> "none"

    is Option<*> -> formatPlain(value.getOrElse { null }, depth, context)

    is String -> sanitize(value)

    is Number, is Boolean -> value.toString()

    is Collection<*> -> renderPlainContainer(value, depth, context) {
      renderCollectionPreview(value, depth, context)
    }

    is Map<*, *> -> renderPlainContainer(value, depth, context) {
      renderMapPreview(value, depth, context)
    }

    else -> sanitize(value.toString())
  }

  private fun renderCollectionPreview(
    value: Collection<*>,
    depth: Int,
    context: ConsoleRenderContext
  ): String {
    if (value.isEmpty()) return "[]"
    val selected = policy.selectCollectionItems(value)
    val printable = selected.items.take(VALUE_PREVIEW_LIMIT)
    return printable.joinToString(
      separator = ", ",
      prefix = "[",
      postfix = if (value.size > printable.size) ", ...]" else "]"
    ) { formatPlain(it.value, depth + 1, context) }
  }

  private fun renderMapPreview(
    value: Map<*, *>,
    depth: Int,
    context: ConsoleRenderContext
  ): String {
    if (value.isEmpty()) return "{}"
    val selected = policy.selectMapEntries(value)
    val printable = selected.entries.take(VALUE_PREVIEW_LIMIT)
    return printable.joinToString(
      separator = ", ",
      prefix = "{",
      postfix = if (value.size > printable.size) ", ...}" else "}"
    ) { (key, nestedValue) -> "$key=${formatPlain(nestedValue, depth + 1, context)}" }
  }

  private inline fun renderContainer(
    value: Any,
    prefix: String,
    depth: Int,
    context: ConsoleRenderContext,
    render: () -> List<String>
  ): List<String> = when (context.enter(value, depth)) {
    ContainerVisit.CYCLE -> listOf("$prefix<CYCLE>")

    ContainerVisit.MAX_DEPTH -> listOf("$prefix<MAX DEPTH REACHED>")

    ContainerVisit.ENTERED ->
      try {
        render()
      } finally {
        context.leave(value)
      }
  }

  private inline fun renderPlainContainer(
    value: Any,
    depth: Int,
    context: ConsoleRenderContext,
    render: () -> String
  ): String = when (context.enter(value, depth)) {
    ContainerVisit.CYCLE -> "<CYCLE>"

    ContainerVisit.MAX_DEPTH -> "<MAX DEPTH REACHED>"

    ContainerVisit.ENTERED ->
      try {
        render()
      } finally {
        context.leave(value)
      }
  }

  private fun newRenderContext(): ConsoleRenderContext =
    ConsoleRenderContext(policy.maxNestingDepth)

  private companion object {
    const val DETAIL_INDENT_STEP = 2
    const val VALUE_PREVIEW_LIMIT = 6
  }
}
