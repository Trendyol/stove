package com.trendyol.stove.dashboard

import arrow.core.*
import com.trendyol.stove.dashboard.api.DashboardEvent
import com.trendyol.stove.events.*
import com.trendyol.stove.reporting.*
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.PluggedSystem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class DashboardObservationsTest : FunSpec({
  test("test duration and timestamps use observation time even when exported together") {
    val received = CopyOnWriteArrayList<DashboardEvent>()
    val server = startMockServer(received, port = 0)
    try {
      Stove().use { stove ->
        val dashboard = DashboardSystem(stove, DashboardSystemOptions("time", ingestion = DashboardIngestion.Grpc(port = server.port)))
        val start = Instant.parse("2026-11-18T10:00:00Z")
        val events = listOf(RunStarting(emptyList()), TestStarted(StoveTestContext("test", "test")), TestEnded("test"), RunFinished())
        val timestamps = listOf(start, start, start.plusSeconds(10), start.plusSeconds(12))
        events.forEachIndexed { index, event ->
          dashboard.observe(EventEnvelope(stove.events.runId, "event-$index", index + 1L, timestamps[index], event))
        }
        dashboard.close()
        received.single { it.hasTestStarted() }.testStarted.timestamp shouldBe start.toTimestamp()
        received.single { it.hasTestEnded() }.testEnded.let {
          it.timestamp shouldBe start.plusSeconds(10).toTimestamp()
          it.durationMs shouldBe 10_000L
        }
        received.last().runEnded.timestamp shouldBe start.plusSeconds(12).toTimestamp()
        received.last().runEnded.durationMs shouldBe 12_000L
      }
    } finally {
      server.shutdownNow()
    }
  }

  test("a failed observation drain still finalizes the run once") {
    val received = CopyOnWriteArrayList<DashboardEvent>()
    val server = startMockServer(received, port = 0)
    try {
      val stove = Stove()
      val dashboard = DashboardSystem(stove, DashboardSystemOptions("drain", ingestion = DashboardIngestion.Grpc(port = server.port)))
      dashboard.run()
      stove.close()
      shouldThrow<IllegalStateException> { dashboard.close() }
      dashboard.close()
      received.count { it.hasRunEnded() } shouldBe 1
    } finally {
      server.shutdownNow()
    }
  }

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
      DashboardObservations(stove.events) { envelope ->
        val event = envelope.event
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
      DashboardObservations(stove.events) { received += it.event }.use { observations ->
        withTimeout(2.seconds) { observations.awaitPending() }
        received.isEmpty() shouldBe true
      }
    }
  }

  test("a failed handler does not prevent terminal observation delivery") {
    Stove().use { stove ->
      val received = CopyOnWriteArrayList<StoveEvent>()
      DashboardObservations(stove.events) {
        if (it.event is RunStarting) throw CancellationException("handler deadline")
        if (it.event is RunReady) error("broken handler")
        received += it.event
      }.use { observations ->
        stove.publish(RunStarting(emptyList()))
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
