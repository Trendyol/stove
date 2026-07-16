package com.trendyol.stove.interactions

import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Ambiguous-but-suggestive evidence the mocks observe. Warnings are diagnostics for the
 * dashboard — they never fail a test — and follow the same certainty rule as attribution:
 * a warning is raised only from provable evidence (tagged stubs, tagged requests), never
 * from inference.
 */
enum class MockWarningKind {
  /**
   * A stub registered by a test was never matched by the time the test ended:
   * dead fixture weight, or a test passing without the interaction it thinks it proves.
   */
  UNUSED_STUB,

  /**
   * A request provably sent by one test was served by a stub registered in another —
   * cross-test bleed that scoping keeps out of assertions but users should see.
   */
  CROSS_TEST_MATCH,

  /**
   * A test ended with unmatched requests provably its own and never called `validate()` —
   * the test would have failed validation had it asked.
   */
  UNVALIDATED_UNMATCHED
}

/** One warning observed by a mock system, attributed to the test it concerns. */
data class MockWarning(
  val system: String,
  val kind: MockWarningKind,
  val testId: String?,
  val message: String,
  val stubId: String? = null,
  /** URL for HTTP, full method name for gRPC — when the warning concerns one target. */
  val target: String? = null,
  val timestamp: Instant = Instant.now()
)

/** Receives mock warnings; implemented by diagnostics consumers (dashboard). */
fun interface MockWarningListener {
  fun onWarning(warning: MockWarning)
}

/** Implemented by mock systems that raise warnings; discovered via `stove.systemsOf`. */
interface MockWarningPublisher {
  fun addWarningListener(listener: MockWarningListener)

  fun removeWarningListener(listener: MockWarningListener)
}

/** Thread-safe listener collection; emission never throws. */
class MockWarningListeners {
  private val logger = LoggerFactory.getLogger(MockWarningListeners::class.java)
  private val listeners = CopyOnWriteArrayList<MockWarningListener>()

  fun add(listener: MockWarningListener) {
    listeners.add(listener)
  }

  fun remove(listener: MockWarningListener) {
    listeners.remove(listener)
  }

  fun emit(warning: MockWarning) {
    listeners.forEach { listener ->
      runCatching { listener.onWarning(warning) }
        .onFailure { e -> logger.warn("Mock warning listener failed: ${e.message}") }
    }
  }
}
