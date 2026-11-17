package com.trendyol.stove.events

import arrow.core.*
import com.trendyol.stove.system.Stove
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.seconds

/** Ordered, hot observations. Collectors own their scope; events have no replay. */
internal class StoveEventBus(private val handlers: List<StoveEventHandler> = emptyList()) : AutoCloseable {
  val runId: String = UUID.randomUUID().toString()
  private val observations = MutableSharedFlow<EventEnvelope>()
  val stream: SharedFlow<EventEnvelope> = observations.asSharedFlow()
  private val lock = ReentrantLock()
  private val connections = ReentrantLock()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  // shortcut: Unbounded buffering preserves synchronous evidence; use durable spooling if collectors routinely fall behind.
  private val pending = Channel<Delivery>(Channel.UNLIMITED)
  private val worker = scope.launch(start = CoroutineStart.LAZY) { dispatch() }
  private var sources: Option<StoveEventSources> = None
  private val capturesInProgress = MutableStateFlow(0)
  private var sequence = 0L
  private enum class Status { OPEN, CLOSING, CLOSED }
  private var status = Status.OPEN

  fun connect(stove: Stove): Unit = connections.withLock {
    val connected = lock.withLock {
      if (status != Status.OPEN || sources.isSome()) return
      StoveEventSources(stove, this).also { sources = Some(it) }
    }
    connected.start()
  }

  fun disconnect(): Unit = connections.withLock {
    val connected = lock.withLock { sources.also { sources = None } }
    connected.onSome { it.close() }
  }

  fun publish(event: StoveEvent) = publish(event, None)

  internal fun publish(event: StoveEvent, source: StoveEventSources) = publish(event, Some(source))

  private fun publish(event: StoveEvent, source: Option<StoveEventSources>) = captureInOrder {
    if (status != Status.OPEN || source.fold({ false }, { sources != Some(it) })) return@captureInOrder None
    enqueue(event)
    sources.flatMap { it.snapshots.plan(event) }
  }

  private inline fun captureInOrder(plan: () -> Option<SnapshotCapture>) {
    val capture = lock.withLock {
      val work = plan().getOrElse { return }
      capturesInProgress.value++
      work
    }
    try {
      capture()
    } finally {
      lock.withLock { capturesInProgress.value-- }
    }
  }

  internal fun recordSnapshot(event: SnapshotRecorded): Unit = lock.withLock {
    if (status != Status.CLOSED) enqueue(event)
  }

  /** Captures unfinished test evidence before systems discard their state during shutdown. */
  fun capturePendingSnapshots() {
    captureInOrder { sources.flatMap { it.snapshots.planPending() } }
    awaitCaptures()
  }

  private fun awaitCaptures(): Unit = runBlocking {
    try {
      withTimeout(30.seconds) { capturesInProgress.first { it == 0 } }
    } catch (_: TimeoutCancellationException) {
      LoggerFactory.getLogger(javaClass).warn("Stove snapshot capture timed out")
    }
  }

  private fun enqueue(event: StoveEvent) {
    val envelope = EventEnvelope(runId, UUID.randomUUID().toString(), ++sequence, Instant.now(), event)
    check(pending.trySend(Delivery.Event(envelope)).isSuccess)
    worker.start()
  }

  /** Returns the last dispatched sequence; collectors acknowledge processing independently. */
  suspend fun flush(): Long {
    val dispatched = CompletableDeferred<Long>()
    while (true) {
      capturesInProgress.first { it == 0 }
      val queued = lock.withLock {
        check(status == Status.OPEN) { "Stove events have been closed" }
        if (capturesInProgress.value != 0) {
          false
        } else {
          check(pending.trySend(Delivery.Flush(sequence, dispatched)).isSuccess)
          worker.start()
          true
        }
      }
      if (queued) break
    }
    val stopped = worker.invokeOnCompletion { dispatched.cancel() }
    return try {
      dispatched.await()
    } finally {
      stopped.dispose()
    }
  }

  private suspend fun dispatch() {
    for (delivery in pending) {
      when (delivery) {
        is Delivery.Event -> {
          observations.emit(delivery.envelope)
          handlers.forEach { handler ->
            try {
              handler(delivery.envelope.event)
            } catch (failure: Throwable) {
              currentCoroutineContext().ensureActive()
              LoggerFactory.getLogger(javaClass).warn("Stove event observer failed", failure)
            }
          }
        }

        is Delivery.Flush -> delivery.dispatched.complete(delivery.sequence)
      }
    }
  }

  override fun close() {
    lock.withLock {
      if (status != Status.OPEN) return
      status = Status.CLOSING
    }
    awaitCaptures()
    disconnect()
    lock.withLock {
      status = Status.CLOSED
      pending.close()
    }
    worker.start()
    runBlocking {
      try {
        withTimeout(30.seconds) { worker.join() }
      } catch (_: TimeoutCancellationException) {
        LoggerFactory.getLogger(javaClass).warn("Stove observation drain timed out")
      } finally {
        scope.cancel()
        pending.cancel()
      }
    }
  }
}

private sealed interface Delivery {
  data class Event(val envelope: EventEnvelope) : Delivery
  data class Flush(val sequence: Long, val dispatched: CompletableDeferred<Long>) : Delivery
}
