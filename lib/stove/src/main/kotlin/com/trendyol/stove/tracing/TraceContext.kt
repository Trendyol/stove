package com.trendyol.stove.tracing

import java.text.Normalizer
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

    /** Span ID length in W3C trace context */
    private const val SPAN_ID_LENGTH = 16

    /** Minimum parts required in a W3C traceparent header */
    private const val TRACEPARENT_MIN_PARTS = 3

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
        .take(SPAN_ID_LENGTH)

    fun parseTraceparent(traceparent: String): Pair<String, String>? {
      val parts = traceparent.split("-")
      return if (parts.size >= TRACEPARENT_MIN_PARTS) {
        parts[1] to parts[2]
      } else {
        null
      }
    }

    /**
     * Sanitizes a string for use in HTTP headers and as a consistent identifier.
     * Replaces non-ASCII characters with their closest ASCII equivalents or underscores.
     *
     * Uses Java's Normalizer to decompose characters (e.g., "ü" → "u" + combining diaeresis)
     * then strips combining marks, leaving only base ASCII characters.
     *
     * For scripts that don't decompose to ASCII (e.g., Japanese, Chinese, Korean),
     * a hash suffix is appended to ensure uniqueness.
     *
     * This should be used when creating testId to ensure consistency between
     * what's stored internally and what's sent in HTTP/gRPC/Kafka headers.
     */
    fun sanitizeToAscii(value: String): String {
      val sanitized = Normalizer
        .normalize(value, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS_REGEX, "")
        .replace(NON_ASCII_REGEX, "_")

      // If we replaced any characters with underscores (lost information),
      // append a hash to ensure uniqueness for non-decomposable scripts like Japanese
      val hasReplacements = sanitized.contains("_") && !value.all { it.code in ASCII_PRINTABLE_START..ASCII_PRINTABLE_END }
      return if (hasReplacements) {
        val hash = Integer.toHexString(value.hashCode() and POSITIVE_INT_MASK).takeLast(HASH_SUFFIX_LENGTH)
        "${sanitized}_$hash"
      } else {
        sanitized
      }
    }

    /** Length of hash suffix for uniqueness */
    private const val HASH_SUFFIX_LENGTH = 6

    /** Start of printable ASCII range (space character) */
    private const val ASCII_PRINTABLE_START = 0x20

    /** End of printable ASCII range (tilde character) */
    private const val ASCII_PRINTABLE_END = 0x7E

    /** Mask to convert hash to positive integer */
    private const val POSITIVE_INT_MASK = 0x7FFFFFFF

    /** Regex to match Unicode combining marks (diacritics) */
    private val COMBINING_MARKS_REGEX = Regex("\\p{M}")

    /** Regex to match any remaining non-ASCII characters */
    private val NON_ASCII_REGEX = Regex("[^\\x20-\\x7E]")
  }
}
