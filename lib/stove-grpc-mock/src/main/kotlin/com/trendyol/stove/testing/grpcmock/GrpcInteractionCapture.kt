package com.trendyol.stove.testing.grpcmock

import io.grpc.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mutable per-call record assembled across the call lifecycle: the interceptor creates it
 * and observes payload sizes and the final status; the stub-matching path fills in match
 * information via [INTERACTION_RECORD]. Emitted exactly once, on close or cancellation —
 * cancellation is how client deadlines become visible, since the handler never learns of them.
 */
internal class InteractionRecord(
  val fullMethodName: String,
  val headers: Map<String, String>,
  val startNanos: Long
) {
  @Volatile var matched: Boolean = false

  @Volatile var stubId: String? = null

  @Volatile var stubTestId: String? = null

  @Volatile var nearMisses: List<String> = emptyList()

  @Volatile var requestMessages: Int = 0

  @Volatile var requestBytes: Long = 0

  @Volatile var responseMessages: Int = 0

  @Volatile var responseBytes: Long = 0

  val emitted: AtomicBoolean = AtomicBoolean(false)
}

/**
 * Carries the current call's [InteractionRecord] to the handler path. Handlers running
 * outside the gRPC context (coroutines) must capture the record before switching threads.
 */
internal val INTERACTION_RECORD: Context.Key<InteractionRecord> = Context.key("stove-interaction-record")
