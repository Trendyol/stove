package com.trendyol.stove.chaos

import kotlin.time.Duration

/** Faults applied to the application's TCP route, leaving Stove's direct connection intact. */
sealed interface NetworkFault {
  /** Closes existing connections and accepts then closes new connections. */
  data object Unavailable : NetworkFault

  /** Pauses forwarding without discarding bytes; TCP backpressure may develop. */
  data object Stall : NetworkFault

  /** Delays each forwarded chunk, not each application request. Healing releases queued chunks. */
  data class Latency(val duration: Duration, val direction: TrafficDirection = TrafficDirection.Both) : NetworkFault {
    init {
      require(duration.isFinite() && duration.isPositive()) { "Latency must be a positive, finite duration" }
    }
  }
}

enum class TrafficDirection { Requests, Responses, Both }

fun stall(): NetworkFault = NetworkFault.Stall

fun latency(duration: Duration, direction: TrafficDirection = TrafficDirection.Both): NetworkFault =
  NetworkFault.Latency(duration, direction)
