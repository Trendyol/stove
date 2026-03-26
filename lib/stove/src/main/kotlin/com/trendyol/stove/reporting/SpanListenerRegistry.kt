package com.trendyol.stove.reporting

/**
 * Interface for systems that accept span event listeners.
 * Lives in the core module so that other modules (e.g. portal) can
 * register listeners without depending on the tracing module directly.
 */
interface SpanListenerRegistry {
  fun addSpanListener(listener: SpanEventListener)
}
