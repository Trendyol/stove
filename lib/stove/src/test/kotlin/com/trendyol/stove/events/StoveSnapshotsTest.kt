package com.trendyol.stove.events

import com.trendyol.stove.reporting.*
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.PluggedSystem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import java.util.concurrent.CompletableFuture
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

  test("a checkpoint leaves test-end evidence pending and end listeners cannot erase captured state") {
    Stove().use { stove ->
      val bytes = byteArrayOf(1)
      val values = mutableListOf(mapOf("bytes" to bytes))
      stove.getOrRegister(object : PluggedSystem, Reports {
        override val stove = stove
        override fun snapshot() = SystemSnapshot("nested", mapOf("values" to values), "")
        override fun close() = Unit
      })
      stove.addReportListener(object : ReportEventListener {
        override fun onTestEnded(testId: String) {
          bytes[0] = 3
          values.clear()
        }
      })
      EventRecorder(stove.startObserving()).use { recorder ->
        stove.startTest(StoveTestContext("test", "test"))
        stove.capturePendingSnapshots()
        bytes[0] = 2
        stove.endTest()
        recorder.drain()
        recorder.events.filterIsInstance<SnapshotRecorded>().map {
          val list = it.snapshot.state.getValue("values") as List<*>
          ((list.single() as Map<*, *>)["bytes"] as ByteArray).single().toInt()
        } shouldBe listOf(1, 2)
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

  test("flush includes snapshots claimed by shutdown for unfinished tests") {
    Stove().use { stove ->
      val system = stove.getOrRegister(DelayedSnapshot(stove))
      EventRecorder(stove.events).use { recorder ->
        stove.startObserving()
        stove.publish(TestStarted(StoveTestContext("unfinished", "unfinished")))
        coroutineScope {
          val capture = async(Dispatchers.IO) { stove.capturePendingSnapshots() }
          try {
            system.entered.await(2, TimeUnit.SECONDS) shouldBe true
            val flush = async(start = CoroutineStart.UNDISPATCHED) { stove.events.flush() }
            system.release.countDown()
            flush.await() shouldBe 2L
          } finally {
            system.release.countDown()
          }
          capture.await()
        }
        recorder.drain()
        recorder.events.filterIsInstance<SnapshotRecorded>().single().testId shouldBe "unfinished"
      }
    }
  }

  test("unfinished tests get independent capture attempts before the common deadline") {
    Stove().use { stove ->
      val second = CountDownLatch(1)
      stove.getOrRegister(object : PluggedSystem, Reports {
        override val stove = stove
        override fun snapshot(): SystemSnapshot {
          val id = checkNotNull(StoveTestContextHolder.get()).testId
          if (id == "first") check(second.await(3, TimeUnit.SECONDS)) else second.countDown()
          return SystemSnapshot("parallel", emptyMap(), id)
        }
        override fun close() = Unit
      })
      EventRecorder(stove.startObserving()).use { recorder ->
        listOf("first", "second").forEach { stove.publish(TestStarted(StoveTestContext(it, it))) }
        stove.capturePendingSnapshots()
        recorder.drain()
        recorder.events.filterIsInstance<SnapshotRecorded>().map { it.snapshot.summary }.toSet() shouldBe setOf("first", "second")
      }
    }
  }

  test("interruption during close still disconnects publishers and closes dispatch") {
    Stove().use { stove ->
      val thread = CompletableFuture<Thread>()
      val system = stove.getOrRegister(DelayedSnapshot(stove))
      val bus = StoveEventBus(snapshotDeadline = { wait ->
        thread.complete(Thread.currentThread())
        wait()
        true
      })
      bus.connect(stove)
      bus.publish(TestStarted(StoveTestContext("test", "test")))
      val end = CompletableFuture.runAsync { bus.publish(TestEnded("test")) }
      try {
        system.entered.await(2, TimeUnit.SECONDS) shouldBe true
        val close = CompletableFuture.supplyAsync { runCatching { bus.close() }.exceptionOrNull() }
        thread.get(2, TimeUnit.SECONDS).interrupt()
        (close.get(2, TimeUnit.SECONDS) is InterruptedException) shouldBe true
      } finally {
        system.release.countDown()
        end.get(2, TimeUnit.SECONDS)
      }
      shouldThrow<IllegalStateException> { bus.flush() }
    }
  }

  test("shutdown can drain and close while a custom snapshot ignores interruption") {
    Stove().use { stove ->
      val system = stove.getOrRegister(UninterruptibleSnapshot(stove))
      val expire = CompletableDeferred<Unit>()
      StoveEventBus(snapshotDeadline = {
        expire.await()
        false
      }).use { events ->
        events.connect(stove)
        stove.startTest(StoveTestContext("unfinished", "unfinished"))
        coroutineScope {
          val cleanup = async(Dispatchers.IO) {
            events.capturePendingSnapshots()
            events.flush()
            events.close()
          }
          try {
            system.entered.await(2, TimeUnit.SECONDS) shouldBe true
            expire.complete(Unit)
            withTimeout(2.seconds) { cleanup.await() }
          } finally {
            expire.complete(Unit)
            system.release.complete(Unit)
          }
        }
      }
    }
  }

  test("a second waiter can cancel another waiter's capture without disabling the next test") {
    Stove().use { stove ->
      val system = stove.getOrRegister(InterruptibleSnapshot(stove))
      val waiting = CompletableDeferred<Unit>()
      StoveEventBus(snapshotDeadline = { wait ->
        if (waiting.complete(Unit)) {
          wait()
          true
        } else {
          false
        }
      }).use { events ->
        events.connect(stove)
        EventRecorder(events).use { recorder ->
          events.publish(TestStarted(StoveTestContext("old", "old")))
          coroutineScope {
            val first = async(Dispatchers.IO) { events.capturePendingSnapshots() }
            try {
              withTimeout(5.seconds) {
                waiting.await()
                system.entered.await()
              }
              events.capturePendingSnapshots()
              withTimeout(5.seconds) {
                first.await()
                system.interrupted.await()
              }
            } finally {
              system.release.countDown()
            }
          }
          events.publish(TestStarted(StoveTestContext("new", "new")))
          events.publish(TestEnded("new"))
          recorder.drain()
          recorder.events.filterIsInstance<SnapshotRecorded>().map { it.testId } shouldBe listOf("new")
        }
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

private class UninterruptibleSnapshot(override val stove: Stove) : PluggedSystem, Reports {
  val entered = CountDownLatch(1)
  val release = CompletableFuture<Unit>()
  override fun snapshot(): SystemSnapshot {
    entered.countDown()
    release.join()
    return SystemSnapshot("Delayed", emptyMap<String, Any>(), "")
  }
  override fun close() = Unit
}

private class InterruptibleSnapshot(override val stove: Stove) : PluggedSystem, Reports {
  val entered = CompletableDeferred<Unit>()
  val interrupted = CompletableDeferred<Unit>()
  val release = CountDownLatch(1)
  override fun snapshot(): SystemSnapshot {
    if (entered.complete(Unit)) {
      try {
        release.await()
      } catch (_: InterruptedException) {
        interrupted.complete(Unit)
      }
    }
    return SystemSnapshot("Interrupted", emptyMap<String, Any>(), "")
  }
  override fun close() = Unit
}
