package com.trendyol.stove.events

import arrow.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Claims share the bus lock with publication, expiry and flush barriers. */
internal class SnapshotCaptures(private val lock: ReentrantLock, private val deadline: SnapshotDeadline) {
  private val logger = LoggerFactory.getLogger(javaClass)
  private val active = MutableStateFlow(emptySet<Job>())
  val outstanding get() = active.value
  val idle get() = active.value.isEmpty()
  fun owns(claim: Job) = claim in active.value

  fun claim(plan: Option<SnapshotCapture>): Option<() -> Unit> = plan.map { capture ->
    val claim = Job().also(::track)
    return@map {
      try {
        claim.capture()
      } finally {
        claim.complete()
      }
    }
  }

  fun launchPending(scope: CoroutineScope, capture: SnapshotCapture) {
    val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
      val claim = currentCoroutineContext().job
      runInterruptible { claim.capture() }
    }
    track(job)
    job.start()
  }

  private fun track(job: Job) {
    active.value += job
    job.invokeOnCompletion { lock.withLock { active.value -= job } }
  }

  suspend fun awaitIdle() = active.first { it.isEmpty() }

  fun awaitDeadline(outstanding: Set<Job> = active.value) {
    if (outstanding.isEmpty()) return
    var settled = false
    try {
      settled = runBlocking { deadline { active.first { live -> outstanding.none(live::contains) } } }
    } finally {
      if (!settled) expire(outstanding)
    }
  }

  private fun expire(outstanding: Set<Job>) {
    val expired = lock.withLock { active.value.intersect(outstanding).also { active.value -= it } }
    expired.forEach { it.cancel() }
    if (expired.isNotEmpty()) {
      logger.warn("Snapshot wait ended with outstanding captures; discarding late results")
    }
  }
}
