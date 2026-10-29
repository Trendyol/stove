package com.trendyol.stove.chaos

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Coordinates fault changes with forwarding workers without owning their sockets. */
internal class TcpTrafficGate {
  private val lock = ReentrantLock()
  private val changed = lock.newCondition()
  private val unavailable = AtomicBoolean(false)
  private val disconnectEpoch = AtomicInteger()
  private var fault: NetworkFault? = null
  private var revision = 0L
  private data class Observation(val revision: Long, val directions: Set<TrafficDirection> = emptySet())
  private val observation = MutableStateFlow(Observation(0))

  fun captureStalledTraffic(direction: TrafficDirection): StalledTrafficObservation {
    require(direction != TrafficDirection.Both) { "Observe one traffic direction at a time" }
    val expected = lock.withLock {
      val current = fault
      check(current is NetworkFault.Stall && current.direction.appliesTo(direction)) {
        "The active fault must stall the observed direction"
      }
      revision
    }
    return StalledTrafficObservation { timeout ->
      require(timeout.isFinite() && timeout.isPositive()) { "Observation timeout must be positive and finite" }
      val result = withTimeout(timeout) {
        observation.first { it.revision != expected || direction in it.directions }
      }
      check(result.revision == expected) { "Fault ended before stalled traffic was observed" }
    }
  }

  val epoch: Int get() = disconnectEpoch.get()

  fun admitsConnection(acceptedEpoch: Int): Boolean =
    !unavailable.get() && acceptedEpoch == epoch

  fun changeFault(next: NetworkFault?) = lock.withLock {
    if (next == NetworkFault.Unavailable) disconnectEpoch.incrementAndGet()
    fault = next
    unavailable.set(next == NetworkFault.Unavailable)
    revision++
    observation.value = Observation(revision)
    changed.signalAll()
  }

  fun wakeWorkers() = lock.withLock { changed.signalAll() }

  // A stall permits one bounded read; only a positive buffered chunk records traffic evidence.
  fun canRead(): Boolean = !unavailable.get()

  fun awaitForwarding(direction: TrafficDirection, isOpen: () -> Boolean): Boolean = lock.withLock {
    while (isOpen()) {
      when (val current = fault) {
        NetworkFault.Unavailable -> return false

        is NetworkFault.Stall -> {
          if (!current.direction.appliesTo(direction)) return true
          observation.value = observation.value.copy(directions = observation.value.directions + direction)
          changed.await()
        }

        is NetworkFault.Latency -> {
          if (!current.direction.appliesTo(direction)) return true
          if (awaitLatency(current, isOpen)) return isOpen()
        }

        null -> return true
      }
    }
    false
  }

  // False means the fault changed; the caller re-evaluates it before forwarding buffered bytes.
  private fun awaitLatency(latency: NetworkFault.Latency, isOpen: () -> Boolean): Boolean {
    val observedRevision = revision
    var remaining = latency.duration.inWholeNanoseconds
    while (remaining > 0 && observedRevision == revision && isOpen()) {
      remaining = changed.awaitNanos(remaining)
    }
    return observedRevision == revision
  }

  private fun TrafficDirection.appliesTo(direction: TrafficDirection): Boolean =
    this == TrafficDirection.Both || this == direction
}
