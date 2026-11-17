package com.trendyol.stove.dashboard

import com.trendyol.stove.events.StoveEventHandler
import com.trendyol.stove.events.StoveEvents
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

internal class DashboardObservations(private val events: StoveEvents, observe: StoveEventHandler) : AutoCloseable {
  private val logger = LoggerFactory.getLogger(javaClass)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val processed = MutableStateFlow(0L)

  init {
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      events.stream.collect { envelope ->
        try {
          observe(envelope.event)
        } catch (failure: Exception) {
          if (failure is CancellationException) throw failure
          logger.warn("Dashboard observation failed", failure)
        } finally {
          processed.value = envelope.sequence
        }
      }
    }
  }

  fun drain(): Unit = runBlocking { awaitPending() }

  suspend fun awaitPending() {
    try {
      withTimeout(30.seconds) {
        val dispatched = events.flush()
        processed.first { it >= dispatched }
      }
    } catch (_: TimeoutCancellationException) {
      currentCoroutineContext().ensureActive()
      logger.warn("Dashboard observation drain timed out")
    }
  }

  override fun close() = scope.cancel()
}
