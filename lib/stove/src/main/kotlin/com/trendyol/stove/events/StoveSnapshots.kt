package com.trendyol.stove.events

import arrow.core.*
import com.trendyol.stove.reporting.*
import com.trendyol.stove.system.Stove
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** Capture before test cleanup; only delivery is asynchronous. Never called under the event lock. */
internal class StoveSnapshots(private val stove: Stove, private val record: SnapshotRecorder) {
  private val logger = LoggerFactory.getLogger(javaClass)
  private val contexts = ConcurrentHashMap<String, StoveTestContext>()
  private val captured = ConcurrentHashMap.newKeySet<String>()

  fun observe(event: StoveEvent) {
    when (event) {
      is TestStarted -> {
        contexts[event.context.testId] = event.context
        captured.remove(event.context.testId)
      }

      is EntryRecorded -> failure(event.entry)

      is TestEnded -> finish(event.testId)
    }
  }

  private fun failure(entry: ReportEntry) {
    if (!entry.isFailed) return
    contexts.getOrNone(entry.testId).onSome { context ->
      if (captured.add(entry.testId)) capture(context, SnapshotTrigger.FAILURE) { it.reportSystemName == entry.system }
    }
  }

  private fun finish(testId: String) {
    contexts.remove(testId).toOption().onSome { capture(it, SnapshotTrigger.TEST_END) }
    captured.remove(testId)
  }

  fun finishPending() = contexts.keys.toList().forEach(::finish)

  private fun capture(context: StoveTestContext, trigger: SnapshotTrigger, filter: (Reports) -> Boolean = { true }) {
    val previous = StoveTestContextHolder.get().toOption()
    try {
      StoveTestContextHolder.set(context)
      stove.systemsOf<Reports>().distinctInstances().forEach { system ->
        runCatching {
          if (filter(system)) record(SnapshotRecorded(context.testId, trigger, system.snapshot()))
        }.onFailure { logger.warn("Failed to capture snapshot from ${system.javaClass.name}", it) }
      }
    } finally {
      previous.fold(StoveTestContextHolder::clear, StoveTestContextHolder::set)
    }
  }
}
