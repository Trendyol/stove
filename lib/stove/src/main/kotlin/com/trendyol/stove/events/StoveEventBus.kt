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
internal class StoveEventBus : AutoCloseable {
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
  private val publishing = MutableStateFlow(0)
  private var sequence = 0L
  private var closing = false
  private var closed = false

  fun connect(stove: Stove): Unit = connections.withLock {
    val connected = lock.withLock {
      if (closing) return
      sources.getOrElse { StoveEventSources(stove, this).also { sources = Some(it) } }
    }
    connected.start()
  }

  fun disconnect(): Unit = connections.withLock {
    val connected = lock.withLock { sources.also { sources = None } }
    connected.onSome { it.close() }
  }

  fun publish(event: StoveEvent) = publish(event, None)

  internal fun publish(event: StoveEvent, source: StoveEventSources) = publish(event, Some(source))

  private fun publish(event: StoveEvent, source: Option<StoveEventSources>) {
    val connected = lock.withLock {
      if (closing || source.fold({ false }, { sources != Some(it) })) return
      publishing.value++
      enqueue(event)
      sources
    }
    try {
      connected.onSome { it.snapshots.observe(event) }
    } finally {
      lock.withLock { publishing.value-- }
    }
  }

  internal fun recordSnapshot(event: SnapshotRecorded): Unit = lock.withLock {
    if (!closed) enqueue(event)
  }

  /** Captures unfinished test evidence before systems discard their state during shutdown. */
  fun capturePendingSnapshots() {
    lock.withLock { sources }.onSome { it.snapshots.finishPending() }
    awaitPublications()
  }

  private fun awaitPublications(): Unit = runBlocking {
    try {
      withTimeout(30.seconds) { publishing.first { it == 0 } }
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
      publishing.first { it == 0 }
      val queued = lock.withLock {
        check(!closing) { "Stove events have been closed" }
        if (publishing.value != 0) {
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
        is Delivery.Event -> observations.emit(delivery.envelope)
        is Delivery.Flush -> delivery.dispatched.complete(delivery.sequence)
      }
    }
  }

  override fun close() {
    lock.withLock {
      if (closing) return
      closing = true
    }
    awaitPublications()
    disconnect()
    lock.withLock {
      closed = true
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
      }
    }
  }
}

private sealed interface Delivery {
  data class Event(val envelope: EventEnvelope) : Delivery
  data class Flush(val sequence: Long, val dispatched: CompletableDeferred<Long>) : Delivery
}
