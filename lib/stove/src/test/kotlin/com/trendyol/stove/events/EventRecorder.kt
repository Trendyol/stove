package com.trendyol.stove.events

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

internal class EventRecorder private constructor(
  source: SharedFlow<EventEnvelope>,
  private val flush: suspend () -> Long
) : AutoCloseable {
  constructor(events: StoveEvents) : this(events.stream, events::flush)
  constructor(events: StoveEventBus) : this(events.stream, events::flush)
  val envelopes = CopyOnWriteArrayList<EventEnvelope>()
  val events get() = envelopes.map { it.event }
  private val processed = MutableStateFlow(0L)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  init {
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      source.collect {
        envelopes += it
        processed.value = it.sequence
      }
    }
  }

  suspend fun drain() = withTimeout(5.seconds) {
    val target = flush()
    processed.first { it >= target }
  }

  override fun close() = scope.cancel()
}
