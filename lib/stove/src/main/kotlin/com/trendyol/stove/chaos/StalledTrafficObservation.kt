package com.trendyol.stove.chaos

import kotlin.time.Duration

/** Captured synchronously while fault ownership is checked, then awaited outside coordinator locks. */
internal fun interface StalledTrafficObservation {
  suspend fun await(timeout: Duration)
}
