@file:Suppress("TooGenericExceptionCaught")

package com.trendyol.stove.portal

import com.trendyol.stove.portal.api.*
import com.trendyol.stove.portal.api.PortalEventServiceGrpcKt.PortalEventServiceCoroutineStub
import io.grpc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * Emits portal events to the CLI via gRPC.
 *
 * Events are buffered in a coroutine channel and drained by a background coroutine.
 * On connection failure, retries with auto-disable after [maxFailures] consecutive failures.
 *
 * Thread-safe: [tryEmit] can be called from any thread.
 */
class PortalEmitter(
  host: String,
  port: Int,
  private val maxFailures: Int = MAX_FAILURES
) {
  private val logger = LoggerFactory.getLogger(PortalEmitter::class.java)
  private val channel: ManagedChannel = ManagedChannelBuilder
    .forAddress(host, port)
    .usePlaintext()
    .build()
  private val stub = PortalEventServiceCoroutineStub(channel)

  // Test runs can emit thousands of spans/entries in a short burst.
  // A bounded queue silently drops lifecycle events and leaves the CLI in a stale state.
  private val eventQueue = Channel<PortalEvent>(Channel.UNLIMITED)
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private val disabled = AtomicBoolean(false)
  private val consecutiveFailures = AtomicInteger(0)
  private val drainJob: Job

  init {
    drainJob = scope.launch { drainLoop() }
  }

  /**
   * Non-blocking emit. Drops the event only if the emitter is disabled or already closed.
   */
  fun tryEmit(event: PortalEvent) {
    if (disabled.get()) return
    val result = eventQueue.trySend(event)
    if (result.isFailure) {
      if (!disabled.get()) {
        logger.debug("Dropping portal event because emitter queue is closed")
      }
    }
  }

  /**
   * Graceful shutdown: drains remaining events (with timeout), then closes the gRPC channel.
   */
  fun close() {
    eventQueue.close()
    // Wait for the existing drainLoop to finish consuming buffered events.
    // Closing the channel causes the `for (event in eventQueue)` iterator to terminate
    // once all buffered events are consumed, so drainJob completes naturally.
    runBlocking { withTimeoutOrNull(DRAIN_TIMEOUT_MS.milliseconds) { drainJob.join() } }
    scope.cancel()
    channel.shutdown()
    try {
      channel.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
    if (!channel.isTerminated) {
      channel.shutdownNow()
    }
  }

  private suspend fun drainLoop() {
    for (event in eventQueue) {
      if (!scope.isActive || disabled.get()) break
      sendSafe(event)
    }
  }

  private suspend fun sendSafe(event: PortalEvent): EventAck? =
    try {
      val ack = stub.sendEvent(event)
      consecutiveFailures.set(0)
      ack
    } catch (e: StatusException) {
      handleFailure(e, isGrpc = true)
      null
    } catch (e: Exception) {
      handleFailure(e, isGrpc = false)
      null
    }

  private fun handleFailure(e: Exception, isGrpc: Boolean) {
    val count = consecutiveFailures.incrementAndGet()
    if (count == 1) {
      if (isGrpc) {
        logger.warn("Portal CLI gRPC error: ${e.message}. Events will be dropped after $maxFailures consecutive failures.")
      } else {
        logger.error("Unexpected portal emitter error: ${e.message}", e)
      }
    }
    if (count >= maxFailures) {
      disabled.set(true)
      logger.info("Portal emitter disabled after $count consecutive failures. Tests will continue normally.")
    }
  }

  companion object {
    private const val MAX_FAILURES = 5
    private const val DRAIN_TIMEOUT_MS = 30000L
    private const val SHUTDOWN_TIMEOUT_SECONDS = 5L
  }
}
