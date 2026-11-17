package com.trendyol.stove.events

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
          shared.emit()
          recorder.drain()
          recorder.events.size shouldBe 1
          shared.attached shouldBe 1
          events.disconnect()
          shared.removed shouldBe 1
        }
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
          healthy.attached shouldBe 1
          broken.emit()
          healthy.emit()
          events.disconnect()
          broken.removed shouldBe 1
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
