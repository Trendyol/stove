package com.trendyol.stove.interactions

import com.trendyol.stove.scoping.TestIdSource
import com.trendyol.stove.scoping.stoveTestIdWithSource
import com.trendyol.stove.tracing.TraceContext
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * How a mock exchange was linked to a test. Attribution is proven-only — there is no
 * inferred level. An exchange is either provably owned by a test (via the explicit
 * test-id header, W3C baggage, or the matched stub's registration tag) or explicitly
 * [UNATTRIBUTED]; consumers must never guess.
 */
enum class InteractionAttribution {
  /** The request carried the `X-Stove-Test-Id` header. */
  PROVEN_HEADER,

  /** The request carried the test id in W3C `baggage` (`stove.test.id`). */
  PROVEN_BAGGAGE,

  /** The request matched a stub registered by a test; the fixture carries the identity. */
  PROVEN_STUB,

  /** No provable link to any test — rendered in a run-level lane, never guessed into one. */
  UNATTRIBUTED
}

/**
 * One completed exchange observed by a mock system — the data behind the dashboard's
 * network-tab/swimlane view. Emitted for every request that reaches the mock, matched
 * or not, attributed or not.
 */
data class MockInteraction(
  /** Report system name of the emitting mock, e.g. `WireMock` or `gRPC Mock [payments]`. */
  val system: String,
  val protocol: Protocol,
  /** HTTP method for HTTP; empty for gRPC (the method lives in [target]). */
  val method: String,
  /** URL for HTTP; full method name (`service/Method`) for gRPC. */
  val target: String,
  val matched: Boolean,
  val stubId: String?,
  /** The owning test, when attribution is proven; null when [attribution] is [InteractionAttribution.UNATTRIBUTED]. */
  val testId: String?,
  val attribution: InteractionAttribution,
  val requestBody: String,
  val requestBodyTruncated: Boolean,
  val responseBody: String,
  val responseBodyTruncated: Boolean,
  /** HTTP status code, WireMock fault name, or gRPC status code name. */
  val status: String,
  /** End-to-end serve latency; null when the mock could not measure it. */
  val latencyMs: Long?,
  /** Why each candidate stub rejected the request; only populated for unmatched exchanges. */
  val nearMisses: List<String>,
  /** W3C trace id when the request carried `traceparent`; links the exchange to spans. */
  val traceId: String?,
  val timestamp: Instant
) {
  enum class Protocol { HTTP, GRPC }

  companion object {
    /** Bodies larger than this are truncated before emission; the flag records that it happened. */
    const val MAX_BODY_CHARS: Int = 8_192

    fun truncated(body: String): Pair<String, Boolean> =
      if (body.length <= MAX_BODY_CHARS) body to false else body.take(MAX_BODY_CHARS) to true
  }
}

/**
 * Resolves the proven attribution for an exchange: request headers/baggage first (the
 * traffic names its test), the matched stub's registration tag second (the fixture
 * names it), otherwise explicitly unattributed. Never inferred.
 */
fun resolveAttribution(
  requestHeaders: Map<String, *>,
  matchedStubTestId: String?
): Pair<String?, InteractionAttribution> {
  requestHeaders.stoveTestIdWithSource()?.let { (testId, source) ->
    return testId to when (source) {
      TestIdSource.HEADER -> InteractionAttribution.PROVEN_HEADER
      TestIdSource.BAGGAGE -> InteractionAttribution.PROVEN_BAGGAGE
    }
  }
  matchedStubTestId?.let { return it to InteractionAttribution.PROVEN_STUB }
  return null to InteractionAttribution.UNATTRIBUTED
}

/** Extracts the trace id from a W3C `traceparent` header, when present and well-formed. */
fun Map<String, *>.traceparentTraceId(): String? =
  entries
    .firstOrNull { it.key.equals(TraceContext.TRACEPARENT_HEADER, ignoreCase = true) }
    ?.value
    ?.toString()
    ?.split('-')
    ?.getOrNull(1)
    ?.takeIf { it.length == TRACE_ID_LENGTH && it.any { char -> char != '0' } }

private const val TRACE_ID_LENGTH = 32

/** Receives every completed mock exchange; implemented by diagnostics consumers (dashboard). */
fun interface MockInteractionListener {
  fun onInteraction(interaction: MockInteraction)
}

/**
 * Implemented by mock systems that observe exchanges. Consumers discover publishers via
 * `stove.systemsOf<MockInteractionPublisher>()` and subscribe, mirroring [com.trendyol.stove.reporting.SpanListenerRegistry].
 */
interface MockInteractionPublisher {
  fun addInteractionListener(listener: MockInteractionListener)

  fun removeInteractionListener(listener: MockInteractionListener)
}

/**
 * Thread-safe listener collection for publishers. Emission never throws: a broken
 * listener must not fail the exchange being served.
 */
class MockInteractionListeners {
  private val logger = LoggerFactory.getLogger(MockInteractionListeners::class.java)
  private val listeners = CopyOnWriteArrayList<MockInteractionListener>()

  fun add(listener: MockInteractionListener) {
    listeners.add(listener)
  }

  fun remove(listener: MockInteractionListener) {
    listeners.remove(listener)
  }

  fun emit(interaction: MockInteraction) {
    listeners.forEach { listener ->
      runCatching { listener.onInteraction(interaction) }
        .onFailure { e -> logger.warn("Mock interaction listener failed: ${e.message}") }
    }
  }
}
