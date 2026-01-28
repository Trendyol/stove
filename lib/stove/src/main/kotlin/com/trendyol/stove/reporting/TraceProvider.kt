package com.trendyol.stove.reporting

import arrow.core.Option
import com.trendyol.stove.tracing.TraceVisualization

/**
 * Interface for systems that can provide execution trace information.
 * Implemented by the tracing system to avoid circular dependencies.
 */
interface TraceProvider {
  /**
   * Gets trace visualization for the current test context.
   * Returns None if no traces are available.
   *
   * @param waitTimeMs How long to wait for spans to be exported (default 300ms)
   */
  fun getTraceVisualizationForCurrentTest(waitTimeMs: Long = 300): Option<TraceVisualization>
}
