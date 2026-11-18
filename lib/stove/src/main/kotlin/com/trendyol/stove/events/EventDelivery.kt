package com.trendyol.stove.events

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

internal class EventDelivery(
  private val scope: CoroutineScope,
  private val observations: MutableSharedFlow<EventEnvelope>,
  private val handlers: List<StoveEventHandler>
) : AutoCloseable {
  // shortcut: Unbounded buffering preserves synchronous evidence; use durable spooling if collectors routinely fall behind.
  private val pending = Channel<Delivery>(Channel.UNLIMITED)
  private val dispatching = ThreadLocal.withInitial { false }
  private val worker = scope.launch(dispatching.asContextElement(true), start = CoroutineStart.LAZY) { dispatch() }

  fun requireExternalCaller() {
    check(!dispatching.get()) { "Cannot drain or close Stove from an event handler" }
  }

  fun enqueue(delivery: Delivery) {
    check(pending.trySend(delivery).isSuccess)
    worker.start()
  }

  suspend fun await(dispatched: CompletableDeferred<Long>): Long {
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
    pending.close()
    worker.start()
    try {
      runBlocking { withTimeout(30.seconds) { worker.join() } }
    } catch (_: TimeoutCancellationException) {
      LoggerFactory.getLogger(javaClass).warn("Stove observation drain timed out")
    } finally {
      scope.cancel()
      pending.cancel()
    }
  }
}

internal sealed interface Delivery {
  data class Event(val envelope: EventEnvelope) : Delivery
  data class Flush(val sequence: Long, val dispatched: CompletableDeferred<Long>) : Delivery
}
