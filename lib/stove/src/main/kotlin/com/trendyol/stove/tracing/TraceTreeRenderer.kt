package com.trendyol.stove.tracing

/**
 * Renders span trees as human-readable text.
 */
object TraceTreeRenderer {
  private const val INDENT = "│  "
  private const val BRANCH = "├─ "
  private const val LAST_BRANCH = "└─ "
  private const val SPACE = "   "
  private const val MAX_STACK_TRACE_LINES = 3

  fun render(
    root: SpanNode,
    includeAttributes: Boolean = true,
    attributePrefixes: List<String> = listOf("db.", "http.", "rpc.", "messaging.")
  ): String {
    val sb = StringBuilder()
    renderNode(sb, root, "", true, includeAttributes, attributePrefixes)
    return sb.toString()
  }

  private fun renderNode(
    sb: StringBuilder,
    node: SpanNode,
    prefix: String,
    isLast: Boolean,
    includeAttributes: Boolean,
    attributePrefixes: List<String>
  ) {
    val connector = when {
      prefix.isEmpty() -> ""
      isLast -> LAST_BRANCH
      else -> BRANCH
    }

    val status = if (node.span.isFailed) "✗" else "✓"
    val failureMarker = if (node.span.isFailed && node.children.none { it.hasFailedDescendants }) {
      " ◄── FAILURE POINT"
    } else {
      ""
    }

    sb.appendLine("$prefix$connector${node.span.operationName} [${node.span.durationMs}ms] $status$failureMarker")

    val childPrefix = prefix + when {
      prefix.isEmpty() -> ""
      isLast -> SPACE
      else -> INDENT
    }

    if (node.span.exception != null && node.span.isFailed) {
      renderException(sb, childPrefix, node.span.exception)
    }

    if (includeAttributes) {
      renderRelevantAttributes(sb, childPrefix, node.span.attributes, attributePrefixes)
    }

    node.children.forEachIndexed { index, child ->
      val isChildLast = index == node.children.lastIndex
      renderNode(sb, child, childPrefix, isChildLast, includeAttributes, attributePrefixes)
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
