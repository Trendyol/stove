package com.trendyol.stove.dashboard

import com.trendyol.stove.dashboard.api.DashboardEvent
import com.trendyol.stove.dashboard.api.DashboardEventBatch
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val DEFAULT_MAX_FAILURES = 5

/**
 * Persists events before returning from [tryEmit], then delivers them in order.
 * Pending records survive process restarts and transport failures. Memory holds at
 * most one delivery batch; the conflated channel carries wakeups, never evidence.
 */
class DashboardEmitter internal constructor(
  ingestion: DashboardIngestion,
  private val maxFailures: Int,
  private val drainWarningIntervalMs: Long,
  spoolOptions: DashboardSpoolOptions = DashboardSpoolOptions()
) {
  constructor(
    ingestion: DashboardIngestion = DashboardIngestion.Grpc(),
    maxFailures: Int = DEFAULT_MAX_FAILURES,
    spoolOptions: DashboardSpoolOptions = DashboardSpoolOptions()
  ) : this(ingestion, maxFailures, DRAIN_WARNING_INTERVAL_MS, spoolOptions)

  init {
    require(maxFailures > 0) { "maxFailures must be greater than zero: $maxFailures" }
    require(drainWarningIntervalMs > 0) { "drainWarningIntervalMs must be greater than zero" }
  }

  private val lifecycleLock = ReentrantLock()
  private val spool = DashboardSpool(ingestion, spoolOptions)
  private val transport = ingestion.createTransport()
  private val logger = LoggerFactory.getLogger(DashboardEmitter::class.java)
  private val wakeup = Channel<Unit>(Channel.CONFLATED)
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private val closed = AtomicBoolean(false)

  @Volatile private var fatalError: Exception? = null

  @Volatile private var lastError: String? = null

  @Volatile private var finalStatus: DashboardDeliveryStatus? = null
  private var consecutiveFailures = 0
  private var batchSupported = true
  private val drainJob: Job = scope.launch {
    try {
      drainLoop()
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      fatalError = error
      lastError = error.message
      logger.error("Dashboard delivery stopped; pending evidence remains in ${spool.path}", error)
    }
  }

  val deliveryStatus: DashboardDeliveryStatus
    get() = lifecycleLock.withLock { finalStatus ?: spool.status().copy(lastError = lastError) }

  /** Durable emit. Disk exhaustion/write failures throw, applying backpressure to the caller. */
  fun tryEmit(event: DashboardEvent): Unit = lifecycleLock.withLock {
    check(!closed.get()) { "Dashboard emitter is closed" }
    fatalError?.let { throw DashboardSpoolException("Dashboard delivery requires attention at ${spool.path}", it) }
    spool.append(event)
    wakeup.trySend(Unit)
  }

  /** Drain while progress is possible; after [maxFailures] shutdown failures, retain pending data. */
  fun close() {
    lifecycleLock.withLock {
      if (!closed.compareAndSet(false, true)) return
    }
    wakeup.trySend(Unit)
    runBlocking {
      while (!drainJob.isCompleted) {
        if (withTimeoutOrNull(drainWarningIntervalMs) {
            drainJob.join()
            true
          } != true
        ) {
          logger.warn("Still waiting for dashboard acknowledgments; pending evidence is in ${spool.path}")
        }
      }
    }
    scope.cancel()
    val remaining = lifecycleLock.withLock {
      try {
        spool.status().copy(lastError = lastError).also { finalStatus = it }
      } finally {
        try {
          spool.close()
        } finally {
          transport.close()
        }
      }
    }
    if (remaining.pendingEvents > 0) {
      logger.warn(
        "Dashboard closed with ${remaining.pendingEvents} pending events in ${spool.path}; the next producer using this endpoint and directory will retry them"
      )
    }
    fatalError?.let { throw DashboardSpoolException("Dashboard delivery stopped at ${spool.path}", it) }
  }

  private suspend fun drainLoop() {
    var shutdownLockWaits = 0
    while (scope.isActive) {
      val lease = spool.tryAcquireDelivery()
      if (lease == null) {
        // Another process owns delivery. Do not block shutdown on that process.
        if (closed.get() && ++shutdownLockWaits >= maxFailures) return
        delay(100)
        continue
      }
      var empty = false
      var failed = false
      lease.use {
        var records = spool.peek(if (batchSupported) MAX_BATCH_EVENTS else 1)
        if (records.isEmpty()) {
          empty = true
        } else {
          if (batchSupported && records.size < MAX_BATCH_EVENTS && !closed.get() && spool.status().pendingEvents == records.size.toLong()) {
            delay(10)
            records = spool.peek(if (batchSupported) MAX_BATCH_EVENTS else 1)
          }
          val events = records.map { it.event }
          val batch = DashboardEventBatch.newBuilder().addAllEvents(events).build()
          val useBatch = batchSupported && batch.serializedSize <= MAX_BATCH_BYTES
          val outcome = if (useBatch) transport.sendBatch(batch) else transport.send(events.first())
          when (outcome) {
            SendOutcome.Accepted -> {
              spool.acknowledge(if (useBatch) records else records.take(1))
              consecutiveFailures = 0
              lastError = null
            }

            SendOutcome.Unsupported -> batchSupported = false

            is SendOutcome.Rejected -> throw DashboardSpoolException("Server rejected pending evidence: ${outcome.reason}")

            is SendOutcome.Failed -> {
              consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(Int.MAX_VALUE - 1)
              lastError = outcome.cause.message
              if (consecutiveFailures == 1) {
                logger.warn("Dashboard ${transport.name} delivery failed; retaining and retrying evidence in ${spool.path}", outcome.cause)
              }
              failed = true
            }
          }
        }
      }
      if (empty) {
        if (closed.get()) return
        // Poll also discovers records appended by other producer processes.
        withTimeoutOrNull(100) { wakeup.receive() }
      } else if (failed) {
        if (closed.get() && consecutiveFailures >= maxFailures) return
        delay(RETRY_BASE_DELAY_MS shl (consecutiveFailures.coerceIn(1, 6) - 1))
      }
    }
  }

  private companion object {
    private const val DRAIN_WARNING_INTERVAL_MS = 30000L
    private const val RETRY_BASE_DELAY_MS = 100L
  }
}
