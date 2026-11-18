package com.trendyol.stove.events

import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.system.abstractions.PluggedSystem
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Duration.Companion.seconds

class StoveEventsTest : FunSpec({
  test("collector failure and cancellation leave other collectors running") {
    StoveEventBus().use { events ->
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
      try {
        val broken = scope.launch(start = CoroutineStart.UNDISPATCHED) { events.stream.collect { error("exporter") } }
        EventRecorder(events).use { received ->
          events.publish(RunReady)
          withTimeout(5.seconds) { broken.join() }
          events.publish(RunReady)
          received.drain()
          received.envelopes.map { it.sequence } shouldBe listOf(1L, 2L)
        }
      } finally {
        scope.cancel()
      }
    }
  }

  test("concurrent producers preserve delivery order and unique identities") {
    StoveEventBus().use { events ->
      EventRecorder(events).use { received ->
        coroutineScope {
          repeat(8) { launch(Dispatchers.Default) { repeat(100) { events.publish(RunReady) } } }
        }
        received.drain()
        received.envelopes.map { it.sequence } shouldBe (1L..800L).toList()
        received.envelopes.map { it.eventId }.distinct().size shouldBe 800
        received.envelopes.map { it.runId }.distinct() shouldBe listOf(events.runId)
      }
    }
  }

  test("observations have no replay while inventory replays its latest value") {
    Stove().use { stove ->
      stove.publish(RunReady)
      stove.events.flush() shouldBe 1L
      stove.getOrRegister(object : PluggedSystem {
        override val stove = stove
        override fun close() = Unit
      })
      stove.applicationUnderTest(object : ApplicationUnderTest<Unit> {
        override suspend fun start(configurations: List<String>) = Unit
        override suspend fun stop() = Unit
      }).run()
      stove.events.flush()
      EventRecorder(stove.events).use { received ->
        stove.publish(RunFinished())
        received.drain()
        received.events shouldBe listOf(RunFinished())
        stove.events.environment.first().first().id shouldBe "component-1"
      }
    }
  }

  test("dispatch flush does not claim a collector has finished processing") {
    StoveEventBus().use { events ->
      coroutineScope {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
          events.stream.collect {
            entered.complete(Unit)
            release.await()
          }
        }
        try {
          events.publish(RunReady)
          withTimeout(5.seconds) {
            entered.await()
            events.flush()
          } shouldBe 1L
          collector.isActive shouldBe true
        } finally {
          release.complete(Unit)
          collector.cancelAndJoin()
        }
      }
    }
  }
})
