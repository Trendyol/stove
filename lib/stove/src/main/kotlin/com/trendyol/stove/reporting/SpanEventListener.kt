package com.trendyol.stove.reporting

import com.trendyol.stove.tracing.SpanInfo

/**
 * Listener for span recording events.
 *
 * Receives callbacks when spans are recorded by [com.trendyol.stove.tracing.StoveTraceCollector].
 * Default no-op implementation — override only what you need.
 */
interface SpanEventListener {
  fun onSpanRecorded(span: SpanInfo) {}
}
