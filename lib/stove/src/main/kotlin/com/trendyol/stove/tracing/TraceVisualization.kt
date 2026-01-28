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
  val tree: String
) {
  companion object {
    fun from(traceId: String, testId: String, spans: List<SpanInfo>): TraceVisualization {
      val visualSpans = spans.map { VisualSpan.from(it) }
      val tree = buildTraceTree(spans)
      return TraceVisualization(
        traceId = traceId,
        testId = testId,
        totalSpans = spans.size,
        failedSpans = spans.count { it.status == SpanStatus.ERROR },
        spans = visualSpans,
        tree = tree
      )
    }

    /**
     * Build a tree visualization of spans using SpanTree and TraceTreeRenderer.
     * Consolidates tree-building logic in a single place.
     */
    private fun buildTraceTree(spans: List<SpanInfo>): String {
      if (spans.isEmpty()) return "No spans in trace"

      val root = SpanTree.build(spans) ?: return "No spans in trace"
      return TraceTreeRenderer.render(root)
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
