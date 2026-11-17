package com.trendyol.stove.events

import com.trendyol.stove.reporting.*
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.PluggedSystem
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class StoveSnapshotsTest : FunSpec({
  test("a broken snapshot cannot suppress other systems or the test end") {
    Stove().use { stove ->
      stove.getOrRegister(object : PluggedSystem, Reports {
        override val stove = stove
        override fun snapshot(): SystemSnapshot = error("broken snapshot")
        override fun close() = Unit
      })
      stove.getOrRegister(SnapshotJournal(stove))
      EventRecorder(stove.events).use { recorder ->
        stove.startObserving()
        stove.startTest(StoveTestContext("test", "test"))
        stove.endTest()
        recorder.drain()
        recorder.events.filterIsInstance<SnapshotRecorded>().single().snapshot.system shouldBe "Journal"
        recorder.events.filterIsInstance<TestEnded>().single().testId shouldBe "test"
      }
    }
  }

  test("captures failure and test-end evidence before the next test clears it") {
    Stove().use { stove ->
      val system = stove.getOrRegister(SnapshotJournal(stove))
      EventRecorder(stove.events).use { recorder ->
        stove.startObserving()
        stove.startTest(StoveTestContext("first", "first"))
        system.value = "first evidence"
        stove.recordReport(ReportEntry.failure("Journal", "first", "attempt", "failed"))
        system.value = "final evidence"
        stove.endTest()
        stove.startTest(StoveTestContext("second", "second"))
        system.value = "cleared"
        recorder.drain()
        val snapshots = recorder.events.filterIsInstance<SnapshotRecorded>()
        snapshots.map { it.snapshot.state["value"] } shouldBe listOf("first evidence", "final evidence")
        snapshots.map { it.trigger } shouldBe listOf(SnapshotTrigger.FAILURE, SnapshotTrigger.TEST_END)
        snapshots.map { it.testId }.distinct() shouldBe listOf("first")
      }
    }
  }

  test("flush waits for an in-flight snapshot before choosing its final sequence") {
    Stove().use { stove ->
      val system = stove.getOrRegister(DelayedSnapshot(stove))
      EventRecorder(stove.events).use { recorder ->
        stove.startObserving()
        stove.startTest(StoveTestContext("test", "test"))
        coroutineScope {
          val end = async(Dispatchers.IO) { stove.publish(TestEnded("test")) }
          system.entered.await(2, TimeUnit.SECONDS) shouldBe true
          val flush = async(start = CoroutineStart.UNDISPATCHED) { recorder.drain() }
          try {
            flush.isCompleted shouldBe false
          } finally {
            system.release.countDown()
          }
          withTimeout(5.seconds) {
            end.await()
            flush.await()
          }
        }
        recorder.events.filterIsInstance<SnapshotRecorded>().size shouldBe 1
      }
    }
  }

  test("closing the dispatcher retains a snapshot already being captured") {
    Stove().use { stove ->
      val system = stove.getOrRegister(DelayedSnapshot(stove))
      StoveEventBus().use { events ->
        EventRecorder(events).use { recorder ->
          events.connect(stove)
          stove.startTest(StoveTestContext("test", "test"))
          coroutineScope {
            val end = async(Dispatchers.IO) { events.publish(TestEnded("test")) }
            system.entered.await(2, TimeUnit.SECONDS) shouldBe true
            launch(Dispatchers.IO) {
              delay(100)
              system.release.countDown()
            }
            events.close()
            system.release.count shouldBe 0L
            end.await()
          }
          withTimeout(5.seconds) {
            while (recorder.events.none { it is SnapshotRecorded }) yield()
          }
        }
      }
    }
  }
})

private class SnapshotJournal(override val stove: Stove) : PluggedSystem, Reports {
  var value = ""
  override val reportSystemName = "Journal"
  override fun snapshot() = SystemSnapshot(reportSystemName, mapOf("value" to value), "")
  override fun close() {
    value = "closed"
  }
}

private class DelayedSnapshot(override val stove: Stove) : PluggedSystem, Reports {
  val entered = CountDownLatch(1)
  val release = CountDownLatch(1)
  override fun snapshot(): SystemSnapshot {
    entered.countDown()
    check(release.await(5, TimeUnit.SECONDS))
    return SystemSnapshot("Delayed", emptyMap<String, Any>(), "")
  }
  override fun close() = Unit
}
