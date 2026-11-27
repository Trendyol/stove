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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.milliseconds

private const val DEFAULT_MAX_FAILURES = 5
private const val MAX_BACKOFF_DOUBLINGS = 5

/**
 * Queues dashboard events and sends them using the selected [ingestion] transport.
 *
 * Events are buffered in a coroutine channel and drained by a background coroutine.
 * On connection failure, retries with auto-disable after [maxFailures] consecutive failures.
 *
 * Thread-safe: [tryEmit] can be called from any thread.
 */
class DashboardEmitter internal constructor(
  ingestion: DashboardIngestion,
  private val maxFailures: Int,
  private val drainWarningIntervalMs: Long,
  private val drainTimeoutMs: Long = 30_000
) {
  constructor(
    ingestion: DashboardIngestion = DashboardIngestion.Grpc(),
    maxFailures: Int = DEFAULT_MAX_FAILURES
  ) : this(ingestion, maxFailures, DRAIN_WARNING_INTERVAL_MS)

  init {
    require(drainTimeoutMs > 0) { "drainTimeoutMs must be greater than zero" }
    require(maxFailures > 0) { "maxFailures must be greater than zero: $maxFailures" }
    require(drainWarningIntervalMs > 0) {
      "drainWarningIntervalMs must be greater than zero: $drainWarningIntervalMs"
    }
  }

  private val transport = ingestion.createTransport()
  private val logger = LoggerFactory.getLogger(DashboardEmitter::class.java)
  private val eventQueue = Channel<DashboardEvent>(Channel.UNLIMITED)
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private val disabled = AtomicBoolean(false)
  private val closed = AtomicBoolean(false)
  private var rejectionLogged = false
  private val emitLock = ReentrantLock()
  private var sequenceByRun = emptyMap<String, Long>()
  private val drainJob: Job = scope.launch { drainLoop() }

  /** Non-blocking emit. Drops the event only if the emitter is disabled or closed. */
  fun tryEmit(event: DashboardEvent): Unit = emitLock.withLock {
    if (disabled.get() || closed.get()) return@withLock
    val sequence = sequenceByRun.getOrDefault(event.runId, 0) + 1
    sequenceByRun += event.runId to sequence
    val identifiedEvent = event.toBuilder()
      .setEventId(UUID.randomUUID().toString())
      .setSequence(sequence)
      .build()
    val result = eventQueue.trySend(identifiedEvent)
    if (result.isFailure && !disabled.get()) {
      logger.debug("Dropping dashboard event because emitter queue is closed")
    }
  }

  /** Drains for at most 30 seconds, then closes the transport; incomplete delivery is logged. */
  fun close() {
    emitLock.withLock {
      if (!closed.compareAndSet(false, true)) return
      eventQueue.close()
    }
    val drained = runBlocking {
      withTimeoutOrNull(drainTimeoutMs.milliseconds) {
        awaitDrain()
        true
      } ?: false
    }
    if (!drained) logger.warn("Dashboard flush timed out; some queued observations were not delivered")
    scope.cancel()
    eventQueue.cancel()
    transport.close()
  }

  private suspend fun awaitDrain() {
    while (!drainJob.isCompleted) {
      val drained = withTimeoutOrNull(drainWarningIntervalMs.milliseconds) {
        drainJob.join()
        true
      } ?: false
      if (!drained) logger.warn("Still waiting for queued dashboard events to be acknowledged")
    }
  }

  private suspend fun drainLoop() {
    for (event in eventQueue) {
      if (!scope.isActive || disabled.get()) break
      sendUntilAcknowledged(event)
    }
  }

  private suspend fun sendUntilAcknowledged(event: DashboardEvent) {
    for (attempt in 1..maxFailures) {
      if (!scope.isActive) return
      when (val outcome = transport.send(event)) {
        SendOutcome.Accepted -> return

        is SendOutcome.Rejected -> {
          handleRejection(outcome.reason)
          return
        }

        is SendOutcome.Failed -> handleFailure(outcome.cause, attempt)
      }
      if (attempt < maxFailures) delay((RETRY_BASE_DELAY_MS shl (attempt.coerceAtMost(MAX_BACKOFF_DOUBLINGS) - 1)).milliseconds)
    }
  }

  private fun handleRejection(reason: String) {
    if (rejectionLogged) return
    rejectionLogged = true
    logger.warn("Dashboard server rejected an event: $reason. Such events are dropped; tests continue normally.")
  }

  private fun handleFailure(error: Exception, consecutiveFailures: Int) {
    if (consecutiveFailures == 1) {
      logger.warn(
        "Dashboard server ${transport.name} error: ${error.message}. " +
          "Events will be dropped after $maxFailures consecutive failures."
      )
    }
    if (consecutiveFailures >= maxFailures) {
      disabled.set(true)
      eventQueue.cancel()
      logger.info(
        "Dashboard emitter disabled after $consecutiveFailures consecutive failures. Tests will continue normally."
      )
    }
  }

  private companion object {
    private const val DRAIN_WARNING_INTERVAL_MS = 30000L
    private const val RETRY_BASE_DELAY_MS = 100L
  }
}
