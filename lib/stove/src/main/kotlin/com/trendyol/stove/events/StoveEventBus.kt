package com.trendyol.stove.events

import arrow.core.*
import com.trendyol.stove.system.Stove
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.seconds

/** Ordered, hot observations. Collectors own their scope; events have no replay. */
internal class StoveEventBus(
  handlers: List<StoveEventHandler> = emptyList(),
  snapshotDeadline: SnapshotDeadline = { wait -> withTimeoutOrNull(30.seconds) { wait() } != null }
) : AutoCloseable {
  val runId: String = UUID.randomUUID().toString()
  private val observations = MutableSharedFlow<EventEnvelope>()
  val stream: SharedFlow<EventEnvelope> = observations.asSharedFlow()
  private val lock = ReentrantLock()
  private val connections = ReentrantLock()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val delivery = EventDelivery(scope, observations, handlers)
  private var sources: Option<StoveEventSources> = None
  private val captures = SnapshotCaptures(lock, snapshotDeadline)
  private var finished = false
  private var sequence = 0L
  private enum class Status { OPEN, CLOSING, CLOSED }
  private var status = Status.OPEN

  fun connect(stove: Stove): Unit = connections.withLock {
    val connected = lock.withLock {
      if (status != Status.OPEN || finished) return
      sources.getOrElse {
        StoveEventSources(stove, this).also {
          sources = Some(it)
          it.start()
        }
      }
    }
    connected.refresh()
  }

  fun disconnect(): Unit = connections.withLock {
    val connected = lock.withLock { sources.also { sources = None } }
    connected.onSome { it.close() }
  }

  fun publish(event: StoveEvent) {
    require(event !is RunFinished) { "Only Stove shutdown can finish a run" }
    publish(event, None)
  }

  internal fun publish(event: StoveEvent, source: StoveEventSources) = publish(event, Some(source))

  private fun publish(event: StoveEvent, source: Option<StoveEventSources>) {
    val capture = lock.withLock {
      if (status != Status.OPEN || finished || source.fold({ false }, { sources != Some(it) })) return
      enqueue(event)
      captures.claim(sources.flatMap { it.snapshots.plan(event) })
    }
    capture.onSome { it() }
  }

  fun finish(event: RunFinished): Unit = lock.withLock {
    if (status == Status.OPEN && !finished) {
      finished = true
      enqueue(event)
    }
  }

  fun requireExternalCaller() = delivery.requireExternalCaller()

  internal fun recordSnapshot(claim: Job, event: SnapshotRecorded): Unit = lock.withLock {
    if (status != Status.CLOSED && !finished && captures.owns(claim)) enqueue(event)
  }

  /** Waits a bounded time for unfinished test evidence before resource cleanup. */
  fun capturePendingSnapshots(consume: Boolean = true) {
    val outstanding = lock.withLock {
      if (status != Status.OPEN || finished) return
      sources.onSome { source -> source.snapshots.planPending(consume).forEach { captures.launchPending(scope, it) } }
      captures.outstanding
    }
    captures.awaitDeadline(outstanding)
  }

  private fun enqueue(event: StoveEvent) {
    val envelope = EventEnvelope(runId, UUID.randomUUID().toString(), ++sequence, Instant.now(), event)
    delivery.enqueue(Delivery.Event(envelope))
  }

  /** Returns the last dispatched sequence; collectors acknowledge processing independently. */
  suspend fun flush(): Long {
    requireExternalCaller()
    val dispatched = CompletableDeferred<Long>()
    while (true) {
      captures.awaitIdle()
      val queued = lock.withLock {
        check(status == Status.OPEN) { "Stove events have been closed" }
        if (!captures.idle) {
          false
        } else {
          delivery.enqueue(Delivery.Flush(sequence, dispatched))
          true
        }
      }
      if (queued) break
    }
    return delivery.await(dispatched)
  }

  override fun close() {
    delivery.requireExternalCaller()
    lock.withLock {
      if (status != Status.OPEN) return
      status = Status.CLOSING
    }
    try {
      captures.awaitDeadline()
    } finally {
      try {
        disconnect()
      } finally {
        lock.withLock { status = Status.CLOSED }
        delivery.close()
      }
    }
  }
}
