package com.trendyol.stove.events

import arrow.core.*
import com.trendyol.stove.reporting.*
import com.trendyol.stove.system.Stove
import kotlinx.coroutines.Job
import org.slf4j.LoggerFactory

/** Plan captures in event order under the bus lock. Invoke the returned work outside the lock. */
internal class StoveSnapshots(private val stove: Stove, private val record: SnapshotRecorder) {
  private val logger = LoggerFactory.getLogger(javaClass)
  private data class PendingTest(val context: StoveTestContext, val capturedFailure: Boolean = false)

  private var pending = emptyMap<String, PendingTest>()

  fun plan(event: StoveEvent): Option<SnapshotCapture> = when (event) {
    is TestStarted -> {
      pending += event.context.testId to PendingTest(event.context)
      None
    }

    is EntryRecorded -> planFailure(event.entry)

    is TestEnded -> pending.getOrNone(event.testId).map { test ->
      pending -= event.testId
      { capture(test.context, SnapshotTrigger.TEST_END) }
    }

    else -> None
  }

  private fun planFailure(entry: ReportEntry): Option<SnapshotCapture> {
    if (!entry.isFailed) return None
    val test = pending.getOrNone(entry.testId).filter { !it.capturedFailure }.getOrElse { return None }
    pending += entry.testId to test.copy(capturedFailure = true)
    return Some { capture(test.context, SnapshotTrigger.FAILURE) { it.reportSystemName == entry.system } }
  }

  fun planPending(): Option<SnapshotCapture> {
    if (pending.isEmpty()) return None
    val unfinished = pending.values
    pending = emptyMap()
    return Some { unfinished.forEach { capture(it.context, SnapshotTrigger.TEST_END) } }
  }

  private fun Job.capture(context: StoveTestContext, trigger: SnapshotTrigger, filter: (Reports) -> Boolean = { true }) {
    val previous = StoveTestContextHolder.get().toOption()
    try {
      StoveTestContextHolder.set(context)
      for (system in stove.systemsOf<Reports>().distinctInstances()) {
        if (!isActive) return
        runCatching {
          if (filter(system)) record(this, SnapshotRecorded(context.testId, trigger, system.snapshot()))
        }.onFailure { logger.warn("Failed to capture snapshot from ${system.javaClass.name}", it) }
      }
    } finally {
      previous.fold(StoveTestContextHolder::clear, StoveTestContextHolder::set)
    }
  }
}
