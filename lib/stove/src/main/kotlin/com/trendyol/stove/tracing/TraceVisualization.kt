package com.trendyol.stove.tracing

/**
 * Data structure for trace visualization in reports.
 * Designed to be serializable and easy to render in different formats.
 */
data class TraceVisualization(
  val traceId: String,
  val testId: String,
  val totalSpans: Int,
  val failedSpans: Int,
  val spans: List<VisualSpan>,
  val tree: String,
  val coloredTree: String
) {
  companion object {
    fun from(traceId: String, testId: String, spans: List<SpanInfo>): TraceVisualization {
      val visualSpans = spans.map { VisualSpan.from(it) }
      val (tree, coloredTree) = buildTraceTrees(spans)
      return TraceVisualization(
        traceId = traceId,
        testId = testId,
        totalSpans = spans.size,
        failedSpans = spans.count { it.status == SpanStatus.ERROR },
        spans = visualSpans,
        tree = tree,
        coloredTree = coloredTree
      )
    }

    /**
     * Build tree visualizations of spans using SpanTree and TraceTreeRenderer.
     * Returns both plain and colored versions for different display contexts.
     */
    private fun buildTraceTrees(spans: List<SpanInfo>): Pair<String, String> {
      if (spans.isEmpty()) return "No spans in trace" to "No spans in trace"

      val root = SpanTree.build(spans) ?: return "No spans in trace" to "No spans in trace"
      return TraceTreeRenderer.render(root) to TraceTreeRenderer.renderColored(root)
    }
  }
}

/**
 * Simplified span representation for visualization
 */
data class VisualSpan(
  val spanId: String,
  val parentSpanId: String?,
  val operationName: String,
  val serviceName: String,
  val durationMs: Double,
  val status: String,
  val attributes: Map<String, String>
) {
  companion object {
    private const val NANOS_TO_MILLIS = 1_000_000L

    fun from(span: SpanInfo): VisualSpan = VisualSpan(
      spanId = span.spanId,
      parentSpanId = span.parentSpanId,
      operationName = span.operationName,
      serviceName = span.serviceName,
      durationMs = calculateDurationMs(span),
      status = span.status.name,
      attributes = span.attributes
    )

    private fun calculateDurationMs(span: SpanInfo): Double =
      if (span.endTimeNanos > 0) {
        (span.endTimeNanos - span.startTimeNanos).toDouble() / NANOS_TO_MILLIS
      } else {
        0.0
      }
  }
}
