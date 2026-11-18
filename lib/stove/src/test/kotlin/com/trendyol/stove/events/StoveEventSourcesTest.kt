package com.trendyol.stove.events

import com.trendyol.stove.interactions.*
import com.trendyol.stove.reporting.SpanEventListener
import com.trendyol.stove.reporting.SpanListenerRegistry
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.PluggedSystem
import com.trendyol.stove.system.abstractions.SystemKey
import com.trendyol.stove.tracing.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StoveEventSourcesTest : FunSpec({
  test("a publisher registered under multiple keys is attached only once") {
    Stove().use { stove ->
      StoveEventBus().use { events ->
        val shared = stove.getOrRegister(Registry(stove, broken = false))
        stove.getOrRegister(Healthy, shared)
        EventRecorder(events).use { recorder ->
          events.connect(stove)
          val late = stove.getOrRegister(Late, Registry(stove, broken = false))
          events.connect(stove)
          late.emit()
          shared.emit()
          late.attached shouldBe 1
          recorder.drain()
          recorder.events.size shouldBe 2
          shared.attached shouldBe 1
          events.disconnect()
          shared.removed shouldBe 1
        }
      }
    }
  }

  test("reconnect retries only the failed interface of a late publisher") {
    Stove().use { stove ->
      val registry = Registry(stove, broken = false)
      val warnings = MockWarningListeners()
      var attempts = 0
      stove.startObserving()
      stove.getOrRegister(object : PluggedSystem by registry, SpanListenerRegistry by registry, MockWarningPublisher {
        override fun addWarningListener(listener: MockWarningListener) {
          if (++attempts == 1) error("not installed")
          stove.startObserving()
          warnings.add(listener)
        }
        override fun removeWarningListener(listener: MockWarningListener) = warnings.remove(listener)
      })
      EventRecorder(stove.events).use { recorder ->
        repeat(3) { stove.startObserving() }
        val warning = MockWarning("mock", MockWarningKind.UNUSED_STUB, "test", "unused")
        warnings.emit(warning)
        recorder.drain()
        recorder.events shouldBe listOf(WarningObserved(warning))
        registry.attached shouldBe 1
        attempts shouldBe 2
      }
    }
  }

  test("throwing registries cannot prevent healthy attachment removal or reconnection") {
    Stove().use { stove ->
      StoveEventBus().use { events ->
        val broken = stove.getOrRegister(Registry(stove, broken = true))
        val healthy = stove.getOrRegister(Healthy, Registry(stove, broken = false))
        EventRecorder(events).use { recorder ->
          events.connect(stove)
          events.connect(stove)
          broken.attached shouldBe 2
          healthy.attached shouldBe 1
          broken.emit()
          healthy.emit()
          events.disconnect()
          broken.removed shouldBe 2
          healthy.removed shouldBe 1
          broken.emit()
          events.connect(stove)
          healthy.attached shouldBe 2
          broken.emit()
          events.disconnect()
          recorder.drain()
          recorder.events.size shouldBe 3
          healthy.removed shouldBe 2
        }
      }
    }
  }
})

private object Late : SystemKey
private object Healthy : SystemKey

private class Registry(override val stove: Stove, private val broken: Boolean) : PluggedSystem, SpanListenerRegistry {
  private val listeners = mutableListOf<SpanEventListener>()
  var attached = 0
  var removed = 0
  override fun addSpanListener(listener: SpanEventListener) {
    listeners += listener
    attached++
    if (broken) error("attachment failed")
  }
  override fun removeSpanListener(listener: SpanEventListener) {
    removed++
    if (broken) error("removal failed")
    listeners -= listener
  }
  fun emit() = listeners.forEach {
    it.onSpanRecorded(SpanInfo("trace", "span", "", "operation", "app", 0, 1, SpanStatus.OK))
  }
  override fun close() = Unit
}
