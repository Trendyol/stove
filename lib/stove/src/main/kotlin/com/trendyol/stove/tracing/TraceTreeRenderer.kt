package com.trendyol.stove.tracing

/**
 * Renders span trees as human-readable text with optional ANSI colors.
 */
@Suppress("TooManyFunctions")
object TraceTreeRenderer {
  private const val INDENT = "│  "
  private const val BRANCH = "├─ "
  private const val LAST_BRANCH = "└─ "
  private const val SPACE = "   "
  private const val MAX_STACK_TRACE_LINES = 3

  // ANSI color codes
  private object Colors {
    const val RESET = "\u001B[0m"
    const val BOLD = "\u001B[1m"
    const val DIM = "\u001B[2m"
    const val RED = "\u001B[31m"
    const val GREEN = "\u001B[32m"
    const val YELLOW = "\u001B[33m"
    const val CYAN = "\u001B[36m"
    const val WHITE = "\u001B[37m"
    const val BRIGHT_RED = "\u001B[91m"
    const val BRIGHT_GREEN = "\u001B[92m"
    const val BRIGHT_YELLOW = "\u001B[93m"
  }

  /**
   * Renders the span tree with ANSI colors for terminal display.
   */
  fun renderColored(
    root: SpanNode,
    includeAttributes: Boolean = true,
    attributePrefixes: List<String> = listOf("db.", "http.", "rpc.", "messaging.")
  ): String {
    val sb = StringBuilder()
    renderNodeColored(sb, root, "", true, includeAttributes, attributePrefixes)
    return sb.toString()
  }

  /**
   * Renders the span tree as plain text (no colors).
   */
  fun render(
    root: SpanNode,
    includeAttributes: Boolean = true,
    attributePrefixes: List<String> = listOf("db.", "http.", "rpc.", "messaging.")
  ): String {
    val sb = StringBuilder()
    renderNode(sb, root, "", true, includeAttributes, attributePrefixes)
    return sb.toString()
  }

  @Suppress("CyclomaticComplexMethod")
  private fun renderNodeColored(
    sb: StringBuilder,
    node: SpanNode,
    prefix: String,
    isLast: Boolean,
    includeAttributes: Boolean,
    attributePrefixes: List<String>
  ) {
    val connector = getConnector(prefix, isLast)
    val childPrefix = getChildPrefix(prefix, isLast)

    appendColoredSpanLine(sb, node, prefix, connector)
    appendExceptionIfFailed(sb, node, childPrefix, colored = true)
    appendAttributesIfEnabled(sb, includeAttributes, childPrefix, node.span.attributes, attributePrefixes, colored = true)

    node.children.forEachIndexed { index, child ->
      renderNodeColored(sb, child, childPrefix, index == node.children.lastIndex, includeAttributes, attributePrefixes)
    }
  }

  private fun appendColoredSpanLine(sb: StringBuilder, node: SpanNode, prefix: String, connector: String) {
    val isFailed = node.span.isFailed
    val statusIcon = if (isFailed) "${Colors.BRIGHT_RED}✗${Colors.RESET}" else "${Colors.BRIGHT_GREEN}✓${Colors.RESET}"
    val durationColor = if (isFailed) Colors.BRIGHT_RED else Colors.DIM
    val nameColor = if (isFailed) Colors.BRIGHT_RED else Colors.WHITE
    val failureMarker = getFailureMarker(node, colored = true)

    sb.appendLine(
      "$prefix$connector$nameColor${node.span.operationName}${Colors.RESET} " +
        "$durationColor[${node.span.durationMs}ms]${Colors.RESET} $statusIcon$failureMarker"
    )
  }

  private fun getFailureMarker(node: SpanNode, colored: Boolean): String {
    val isFailurePoint = node.span.isFailed && node.children.none { it.hasFailedDescendants }
    return when {
      !isFailurePoint -> ""
      colored -> " ${Colors.BOLD}${Colors.BRIGHT_YELLOW}◄── FAILURE POINT${Colors.RESET}"
      else -> " ◄── FAILURE POINT"
    }
  }

  private fun getConnector(prefix: String, isLast: Boolean): String = when {
    prefix.isEmpty() -> ""
    isLast -> LAST_BRANCH
    else -> BRANCH
  }

  private fun getChildPrefix(prefix: String, isLast: Boolean): String = prefix + when {
    prefix.isEmpty() -> ""
    isLast -> SPACE
    else -> INDENT
  }

  private fun appendExceptionIfFailed(sb: StringBuilder, node: SpanNode, childPrefix: String, colored: Boolean) {
    if (node.span.exception != null && node.span.isFailed) {
      if (colored) {
        renderExceptionColored(sb, childPrefix, node.span.exception)
      } else {
        renderException(sb, childPrefix, node.span.exception)
      }
    }
  }

  private fun appendAttributesIfEnabled(
    sb: StringBuilder,
    includeAttributes: Boolean,
    childPrefix: String,
    attributes: Map<String, String>,
    attributePrefixes: List<String>,
    colored: Boolean
  ) {
    if (includeAttributes) {
      if (colored) {
        renderRelevantAttributesColored(sb, childPrefix, attributes, attributePrefixes)
      } else {
        renderRelevantAttributes(sb, childPrefix, attributes, attributePrefixes)
      }
    }
  }

  private fun renderExceptionColored(sb: StringBuilder, prefix: String, exception: ExceptionInfo) {
    sb.appendLine(
      "$prefix${Colors.DIM}│${Colors.RESET}  ${Colors.BRIGHT_RED}Error:${Colors.RESET} " +
        "${Colors.YELLOW}${exception.type}${Colors.RESET}: ${exception.message}"
    )
    exception.stackTrace
      .take(MAX_STACK_TRACE_LINES)
      .forEach { line ->
        sb.appendLine("$prefix${Colors.DIM}│${Colors.RESET}    ${Colors.DIM}$line${Colors.RESET}")
      }
  }

  private fun renderRelevantAttributesColored(
    sb: StringBuilder,
    prefix: String,
    attributes: Map<String, String>,
    attributePrefixes: List<String>
  ) {
    val relevantAttrs = attributes.filter { (key, _) ->
      attributePrefixes.any { key.startsWith(it) }
    }
    relevantAttrs.forEach { (key, value) ->
      sb.appendLine("$prefix${Colors.DIM}│${Colors.RESET}  ${Colors.CYAN}$key${Colors.RESET}: $value")
    }
  }

  private fun renderNode(
    sb: StringBuilder,
    node: SpanNode,
    prefix: String,
    isLast: Boolean,
    includeAttributes: Boolean,
    attributePrefixes: List<String>
  ) {
    val connector = getConnector(prefix, isLast)
    val childPrefix = getChildPrefix(prefix, isLast)
    val status = if (node.span.isFailed) "✗" else "✓"
    val failureMarker = getFailureMarker(node, colored = false)

    sb.appendLine("$prefix$connector${node.span.operationName} [${node.span.durationMs}ms] $status$failureMarker")

    appendExceptionIfFailed(sb, node, childPrefix, colored = false)
    appendAttributesIfEnabled(sb, includeAttributes, childPrefix, node.span.attributes, attributePrefixes, colored = false)

    node.children.forEachIndexed { index, child ->
      renderNode(sb, child, childPrefix, index == node.children.lastIndex, includeAttributes, attributePrefixes)
    }
  }

  private fun renderException(sb: StringBuilder, prefix: String, exception: ExceptionInfo) {
    sb.appendLine("$prefix${INDENT}Error: ${exception.type}: ${exception.message}")
    exception.stackTrace
      .take(MAX_STACK_TRACE_LINES)
      .forEach { line ->
        sb.appendLine("$prefix$INDENT  $line")
      }
  }

  private fun renderRelevantAttributes(
    sb: StringBuilder,
    prefix: String,
    attributes: Map<String, String>,
    attributePrefixes: List<String>
  ) {
    val relevantAttrs = attributes.filter { (key, _) ->
      attributePrefixes.any { key.startsWith(it) }
    }
    relevantAttrs.forEach { (key, value) ->
      sb.appendLine("$prefix${INDENT}$key: $value")
    }
  }

  fun renderCompact(root: SpanNode): String {
    val sb = StringBuilder()
    renderCompactNode(sb, root, 0)
    return sb.toString()
  }

  private fun renderCompactNode(
    sb: StringBuilder,
    node: SpanNode,
    depth: Int
  ) {
    val indent = "  ".repeat(depth)
    val status = if (node.span.isFailed) "✗" else "✓"
    sb.appendLine("$indent$status ${node.span.operationName} (${node.span.durationMs}ms)")
    node.children.forEach { child ->
      renderCompactNode(sb, child, depth + 1)
    }
  }

  fun renderSummary(root: SpanNode): String {
    val totalSpans = root.spanCount
    val failedSpans = root.flatten().count { it.isFailed }
    val totalDuration = root.span.durationMs
    val maxDepth = root.depth

    return buildString {
      appendLine("Trace Summary:")
      appendLine("  Total spans: $totalSpans")
      appendLine("  Failed spans: $failedSpans")
      appendLine("  Total duration: ${totalDuration}ms")
      appendLine("  Max depth: $maxDepth")

      if (failedSpans > 0) {
        val failurePoint = root.findFailurePoint()
        if (failurePoint != null) {
          appendLine("  Failure point: ${failurePoint.span.operationName}")
          failurePoint.span.exception?.let { ex ->
            appendLine("  Error: ${ex.type}: ${ex.message}")
          }
        }
      }
    }
  }
}
