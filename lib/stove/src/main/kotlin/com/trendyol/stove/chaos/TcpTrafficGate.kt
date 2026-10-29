package com.trendyol.stove.chaos

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

  val epoch: Int get() = disconnectEpoch.get()

  fun admitsConnection(acceptedEpoch: Int): Boolean =
    !unavailable.get() && acceptedEpoch == epoch

  fun changeFault(next: NetworkFault?) = lock.withLock {
    if (next == NetworkFault.Unavailable) disconnectEpoch.incrementAndGet()
    fault = next
    unavailable.set(next == NetworkFault.Unavailable)
    revision++
    changed.signalAll()
  }

  fun wakeWorkers() = lock.withLock { changed.signalAll() }

  fun awaitForwarding(
    direction: TrafficDirection,
    delayChunk: Boolean,
    isOpen: () -> Boolean
  ): Boolean = lock.withLock {
    while (isOpen()) {
      when (val current = fault) {
        NetworkFault.Unavailable -> return false

        NetworkFault.Stall -> changed.await()

        is NetworkFault.Latency -> {
          if (!delayChunk || !current.appliesTo(direction)) return true
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

  private fun NetworkFault.Latency.appliesTo(direction: TrafficDirection): Boolean =
    this.direction == TrafficDirection.Both || this.direction == direction
}
