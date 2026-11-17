package com.trendyol.stove.events

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Read-only observations owned by a Stove instance. Collect before startup; events have no replay. */
class StoveEvents internal constructor(
  val runId: String,
  val stream: SharedFlow<EventEnvelope>,
  val environment: StateFlow<List<RunComponent>>,
  private val dispatchPending: EventDrain
) {
  /** Returns the last dispatched sequence; collectors acknowledge processing independently. */
  suspend fun flush(): Long = dispatchPending()
}
