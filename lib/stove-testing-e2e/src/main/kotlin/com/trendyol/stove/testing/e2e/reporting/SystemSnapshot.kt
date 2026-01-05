package com.trendyol.stove.testing.e2e.reporting

/**
 * Snapshot of a system's state at a point in time.
 * Used for debugging test failures by providing context about what the system was doing.
 *
 * Systems with rich internal state (like Kafka's MessageStore or WireMock's stubs)
 * should override [Reports.snapshot] to provide detailed state information.
 */
data class SystemSnapshot(
  val system: String,
  val state: Map<String, Any>,
  val summary: String
)
