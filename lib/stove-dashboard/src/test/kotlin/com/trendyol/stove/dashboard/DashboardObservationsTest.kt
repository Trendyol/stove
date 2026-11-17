package com.trendyol.stove.dashboard

import arrow.core.*
import com.trendyol.stove.dashboard.api.DashboardEvent
import com.trendyol.stove.events.*
import com.trendyol.stove.reporting.*
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.PluggedSystem
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class DashboardObservationsTest : FunSpec({
  test("unserializable state preserves the snapshot identity and summary") {
    val event = SnapshotRecorded("test", SnapshotTrigger.TEST_END, SystemSnapshot("Custom", mapOf("state" to Any()), "evidence"))
    val snapshot = event.toProto()
    snapshot.testId shouldBe "test"
    snapshot.system shouldBe "Custom"
    snapshot.summary shouldBe "evidence"
    snapshot.stateJson shouldBe "{}"
  }

  test("snapshots keep test context and can wait for another thread to publish") {
    Stove().use { stove ->
      stove.getOrRegister(PublishingSnapshot(stove))
      val received = CopyOnWriteArrayList<DashboardEvent>()
      stove.startObserving()
      val context = StoveTestContext("original-test", "test")
      DashboardObservations(stove.events) { event ->
        if (event is SnapshotRecorded) received += DashboardEvent.newBuilder().setSnapshot(event.toProto()).build()
      }.use { observations ->
        stove.publish(TestStarted(context))
        stove.publish(TestEnded(context.testId))
        observations.drain()
        received.single().snapshot.testId shouldBe context.testId
        received.single().snapshot.stateJson shouldBe """{"testId":"original-test"}"""
      }
    }
  }

  test("snapshot restores the previous thread context") {
    Stove().use { stove ->
      stove.getOrRegister(PublishingSnapshot(stove))
      val previous = StoveTestContext("previous", "previous")
      StoveTestContextHolder.set(previous)
      try {
        stove.startObserving()
        stove.publish(TestStarted(StoveTestContext("test", "test")))
        stove.publish(TestEnded("test"))
        StoveTestContextHolder.get() shouldBe previous
      } finally {
        StoveTestContextHolder.clear()
      }
    }
  }

  test("a late subscription drains without waiting for events it missed") {
    Stove().use { stove ->
      stove.publish(RunReady)
      stove.events.flush()
      val received = CopyOnWriteArrayList<StoveEvent>()
      DashboardObservations(stove.events) { received += it }.use { observations ->
        withTimeout(2.seconds) { observations.awaitPending() }
        received.isEmpty() shouldBe true
      }
    }
  }

  test("a failed handler does not prevent terminal observation delivery") {
    Stove().use { stove ->
      val received = CopyOnWriteArrayList<StoveEvent>()
      DashboardObservations(stove.events) {
        if (it is RunReady) error("broken handler")
        received += it
      }.use { observations ->
        stove.publish(RunReady)
        stove.publish(RunFinished())
        observations.drain()
        received shouldBe listOf(RunFinished())
      }
    }
  }
})

private class PublishingSnapshot(override val stove: Stove) : PluggedSystem, Reports {
  override fun snapshot(): SystemSnapshot {
    CompletableFuture.runAsync { stove.publish(RunReady) }.get(2, TimeUnit.SECONDS)
    val testId = StoveTestContextHolder.get().toOption().map { it.testId }.getOrElse { error("Missing test context") }
    return SystemSnapshot("PublishingSnapshot", mapOf("testId" to testId), "")
  }
  override fun close() = Unit
}
