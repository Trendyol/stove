package com.trendyol.stove.dashboard

import com.trendyol.stove.events.StoveEventHandler
import com.trendyol.stove.events.StoveEvents
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

internal class DashboardObservations(private val events: StoveEvents, observe: StoveEventHandler) : AutoCloseable {
  private val logger = LoggerFactory.getLogger(javaClass)
  private val dispatcher = Dispatchers.Default.limitedParallelism(1)
  private val scope = CoroutineScope(SupervisorJob() + dispatcher)

  init {
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      events.stream.collect { envelope ->
        try {
          observe(envelope.event)
        } catch (failure: Exception) {
          if (failure is CancellationException) throw failure
          logger.warn("Dashboard observation failed", failure)
        }
      }
    }
  }

  fun drain(): Unit = runBlocking { awaitPending() }

  suspend fun awaitPending() {
    try {
      withTimeout(30.seconds) {
        events.flush()
        // The unbuffered flow has handed off each event. Join the collector dispatcher
        // behind the synchronous handler that may still be processing the last one.
        scope.launch { }.join()
      }
    } catch (_: TimeoutCancellationException) {
      currentCoroutineContext().ensureActive()
      logger.warn("Dashboard observation drain timed out")
    }
  }

  override fun close() = scope.cancel()
}
