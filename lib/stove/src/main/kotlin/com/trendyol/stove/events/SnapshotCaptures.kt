package com.trendyol.stove.events

import arrow.core.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/** Claims and abandonment share the event bus lock with publication and flush barriers. */
internal class SnapshotCaptures(private val lock: ReentrantLock, private val timeout: Duration) {
  private val active = MutableStateFlow(0)
  val idle get() = active.value == 0

  @Volatile var accepting = true
    private set

  fun claim(plan: () -> Option<SnapshotCapture>): Option<SnapshotCapture> {
    if (!accepting) return None
    return plan().map { capture ->
      active.value++
      {
        try {
          capture()
        } finally {
          lock.withLock { if (accepting) active.value-- }
        }
      }
    }
  }

  suspend fun awaitIdle() {
    active.first { it == 0 }
  }

  fun awaitDeadline(): Boolean {
    try {
      runBlocking { withTimeout(timeout) { awaitIdle() } }
      return true
    } catch (_: TimeoutCancellationException) {
      return abandon()
    }
  }

  private fun abandon(): Boolean {
    lock.withLock {
      if (idle) return true
      accepting = false
      active.value = 0
    }
    LoggerFactory.getLogger(javaClass).warn("Stove snapshot capture timed out; continuing cleanup without late snapshots")
    return false
  }
}
