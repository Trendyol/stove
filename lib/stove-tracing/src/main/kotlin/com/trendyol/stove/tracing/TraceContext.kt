package com.trendyol.stove.tracing

import java.util.UUID

data class TraceContext(
  val traceId: String,
  val testId: String,
  val rootSpanId: String
) {
  fun toTraceparent(): String = "00-$traceId-$rootSpanId-01"

  companion object {
    /** W3C trace context header name */
    const val TRACEPARENT_HEADER = "traceparent"

    /** Stove test ID header name */
    const val STOVE_TEST_ID_HEADER = "X-Stove-Test-Id"

    /**
     * Uses InheritableThreadLocal to propagate trace context to child threads.
     * IMPORTANT: Always call [clear] when done with the trace to avoid memory leaks.
     * Prefer using [use] for automatic cleanup.
     */
    private val current = InheritableThreadLocal<TraceContext>()

    fun start(testId: String): TraceContext {
      val ctx = TraceContext(
        traceId = generateTraceId(),
        testId = testId,
        rootSpanId = generateSpanId()
      )
      current.set(ctx)
      return ctx
    }

    fun current(): TraceContext? = current.get()

    /**
     * Clears the current trace context from the thread-local storage.
     * IMPORTANT: Always call this method when done with a trace to prevent memory leaks.
     */
    fun clear() = current.remove()

    /**
     * Executes the given block with a new trace context, ensuring cleanup afterward.
     * This is the preferred way to use trace contexts to avoid memory leaks.
     *
     * @param testId The test identifier for the trace
     * @param block The code block to execute within the trace context
     * @return The result of the block execution
     */
    inline fun <T> use(testId: String, block: (TraceContext) -> T): T {
      val ctx = start(testId)
      return try {
        block(ctx)
      } finally {
        clear()
      }
    }

    fun generateTraceId(): String =
      UUID.randomUUID().toString().replace("-", "")

    fun generateSpanId(): String =
      UUID
        .randomUUID()
        .toString()
        .replace("-", "")
        .take(TracingConstants.SPAN_ID_LENGTH)

    fun parseTraceparent(traceparent: String): Pair<String, String>? {
      val parts = traceparent.split("-")
      return if (parts.size >= TracingConstants.TRACEPARENT_MIN_PARTS) {
        parts[1] to parts[2]
      } else {
        null
      }
    }
  }
}
