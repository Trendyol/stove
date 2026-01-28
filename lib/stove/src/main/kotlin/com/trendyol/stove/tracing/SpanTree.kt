package com.trendyol.stove.tracing

/**
 * Represents a node in the span tree.
 * This is an immutable data structure - children cannot be modified after construction.
 */
data class SpanNode(
  val span: SpanInfo,
  val children: List<SpanNode> = emptyList()
) {
  val hasFailedDescendants: Boolean
    get() = span.isFailed || children.any { it.hasFailedDescendants }

  val totalDurationMs: Long
    get() = span.durationMs

  val depth: Int
    get() = 1 + (children.maxOfOrNull { it.depth } ?: 0)

  val spanCount: Int
    get() = 1 + children.sumOf { it.spanCount }

  fun findFailurePoint(): SpanNode? {
    if (span.isFailed && children.none { it.hasFailedDescendants }) {
      return this
    }
    return children.firstNotNullOfOrNull { it.findFailurePoint() }
  }

  fun flatten(): List<SpanInfo> = listOf(span) + children.flatMap { it.flatten() }
}

/**
 * Utility object for building and querying span trees.
 */
object SpanTree {
  fun build(spans: List<SpanInfo>): SpanNode? {
    if (spans.isEmpty()) return null

    val spanMap = spans.associateBy { it.spanId }
    val childrenMap = mutableMapOf<String?, MutableList<SpanInfo>>()

    // Group spans by their parent ID
    for (span in spans) {
      val effectiveParentId = if (span.parentSpanId != null && spanMap.containsKey(span.parentSpanId)) {
        span.parentSpanId
      } else {
        null
      }
      childrenMap.getOrPut(effectiveParentId) { mutableListOf() }.add(span)
    }

    // Recursively build nodes bottom-up (immutably)
    fun buildNode(spanInfo: SpanInfo): SpanNode {
      val childSpans = childrenMap[spanInfo.spanId] ?: emptyList()
      val sortedChildren = childSpans
        .sortedBy { it.startTimeNanos }
        .map { buildNode(it) }
      return SpanNode(spanInfo, sortedChildren)
    }

    val roots = childrenMap[null] ?: return null
    if (roots.isEmpty()) return null

    val sortedRoots = roots.sortedBy { it.startTimeNanos }

    return if (sortedRoots.size == 1) {
      buildNode(sortedRoots.first())
    } else {
      // Create a virtual root containing multiple roots
      val rootNodes = sortedRoots.map { buildNode(it) }
      SpanNode(
        span = sortedRoots.first().copy(
          operationName = "trace-root",
          parentSpanId = null
        ),
        children = rootNodes
      )
    }
  }

  fun findSpan(root: SpanNode, predicate: (SpanInfo) -> Boolean): SpanNode? {
    if (predicate(root.span)) return root
    return root.children.firstNotNullOfOrNull { findSpan(it, predicate) }
  }

  fun filterSpans(root: SpanNode, predicate: (SpanInfo) -> Boolean): List<SpanNode> {
    val result = mutableListOf<SpanNode>()
    if (predicate(root.span)) result.add(root)
    root.children.forEach { result.addAll(filterSpans(it, predicate)) }
    return result
  }
}
