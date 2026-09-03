package com.trendyol.stove.dashboard

import com.trendyol.stove.dashboard.api.DashboardEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

private const val DEFAULT_MAX_FAILURES = 5

/**
 * Queues dashboard events and sends them using the selected [ingestion] transport.
 *
 * Events are buffered in a coroutine channel and drained by a background coroutine.
 * On connection failure, retries with auto-disable after [maxFailures] consecutive failures.
 *
 * Thread-safe: [tryEmit] can be called from any thread.
 */
class DashboardEmitter(
  ingestion: DashboardIngestion = DashboardIngestion.Grpc(),
  private val maxFailures: Int = DEFAULT_MAX_FAILURES
) {
  init {
    require(maxFailures > 0) { "maxFailures must be greater than zero: $maxFailures" }
  }

  private val transport = ingestion.createTransport()
  private val logger = LoggerFactory.getLogger(DashboardEmitter::class.java)
  private val eventQueue = Channel<DashboardEvent>(Channel.UNLIMITED)
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private val disabled = AtomicBoolean(false)
  private val closed = AtomicBoolean(false)
  private var consecutiveFailures = 0
  private var rejectionLogged = false
  private val sequenceByRun = mutableMapOf<String, Long>()
  private val drainJob: Job = scope.launch { drainLoop() }

  /** Non-blocking emit. Drops the event only if the emitter is disabled or closed. */
  @Synchronized
  fun tryEmit(event: DashboardEvent) {
    if (disabled.get() || closed.get()) return
    val sequence = sequenceByRun.getOrDefault(event.runId, 0) + 1
    sequenceByRun[event.runId] = sequence
    val identifiedEvent = event.toBuilder()
      .setEventId(UUID.randomUUID().toString())
      .setSequence(sequence)
      .build()
    val result = eventQueue.trySend(identifiedEvent)
    if (result.isFailure && !disabled.get()) {
      logger.debug("Dropping dashboard event because emitter queue is closed")
    }
  }

  /** Graceful, idempotent shutdown: drains queued events, then closes the transport. */
  fun close() {
    if (!closed.compareAndSet(false, true)) return
    eventQueue.close()
    runBlocking { withTimeoutOrNull(DRAIN_TIMEOUT_MS.milliseconds) { drainJob.join() } }
    scope.cancel()
    transport.close()
  }

  private suspend fun drainLoop() {
    for (event in eventQueue) {
      if (!scope.isActive || disabled.get()) break
      sendUntilAcknowledged(event)
    }
  }

  private suspend fun sendUntilAcknowledged(event: DashboardEvent) {
    while (scope.isActive && !disabled.get()) {
      when (val outcome = transport.send(event)) {
        SendOutcome.Accepted -> {
          consecutiveFailures = 0
          return
        }

        is SendOutcome.Rejected -> {
          consecutiveFailures = 0
          if (!rejectionLogged) {
            rejectionLogged = true
            logger.warn(
              "Dashboard CLI rejected an event: ${outcome.reason}. " +
                "Such events are dropped; tests continue normally."
            )
          }
          return
        }

        is SendOutcome.Failed -> handleFailure(outcome.cause)
      }

      if (!disabled.get()) {
        val attempt = consecutiveFailures.coerceAtLeast(1).coerceAtMost(5)
        delay((RETRY_BASE_DELAY_MS shl (attempt - 1)).milliseconds)
      }
    }
  }

  private fun handleFailure(error: Exception) {
    consecutiveFailures++
    if (consecutiveFailures == 1) {
      logger.warn(
        "Dashboard CLI ${transport.name} error: ${error.message}. " +
          "Events will be dropped after $maxFailures consecutive failures."
      )
    }
    if (consecutiveFailures >= maxFailures) {
      disabled.set(true)
      logger.info(
        "Dashboard emitter disabled after $consecutiveFailures consecutive failures. Tests will continue normally."
      )
    }
  }

  private companion object {
    private const val DRAIN_TIMEOUT_MS = 30000L
    private const val RETRY_BASE_DELAY_MS = 100L
  }
}
