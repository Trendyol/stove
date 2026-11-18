package com.trendyol.stove.events

import com.trendyol.stove.interactions.*
import com.trendyol.stove.reporting.*
import com.trendyol.stove.system.Stove
import com.trendyol.stove.tracing.SpanInfo
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.IdentityHashMap

/** Bridges existing observations without changing the callbacks that maintain test state. */
internal class StoveEventSources(private val stove: Stove, private val events: StoveEventBus, accepting: () -> Boolean) :
  ReportEventListener, SpanEventListener, MockInteractionListener, MockWarningListener, AutoCloseable {
  val snapshots = StoveSnapshots(stove, events::recordSnapshot, accepting)
  private val logger = LoggerFactory.getLogger(javaClass)
  private val attached = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

  fun start() = stove.addReportListener(this)

  fun refresh() {
    val added = stove.allRegisteredSystems().filter(attached::add)
    added.filterIsInstance<SpanListenerRegistry>().observe { it.addSpanListener(this) }
    added.filterIsInstance<MockInteractionPublisher>().observe { it.addInteractionListener(this) }
    added.filterIsInstance<MockWarningPublisher>().observe { it.addWarningListener(this) }
  }

  override fun onTestStarted(ctx: StoveTestContext) = emit(TestStarted(ctx))
  override fun onTestFailed(testId: String, error: String) = emit(TestFailed(testId, error))
  override fun onTestEnded(testId: String) = emit(TestEnded(testId))
  override fun onEntryRecorded(entry: ReportEntry) = emit(EntryRecorded(entry))
  override fun onSpanRecorded(span: SpanInfo) = emit(SpanRecorded(span))
  override fun onInteraction(interaction: MockInteraction) = emit(InteractionObserved(interaction))
  override fun onWarning(warning: MockWarning) = emit(WarningObserved(warning))

  private fun emit(event: StoveEvent) = events.publish(event, this)

  private fun <T> List<T>.observe(action: (T) -> Unit) = forEach { publisher ->
    runCatching { action(publisher) }.onFailure { logger.warn("Stove observation attachment or removal failed", it) }
  }

  override fun close() {
    stove.removeReportListener(this)
    attached.filterIsInstance<SpanListenerRegistry>().observe { it.removeSpanListener(this) }
    attached.filterIsInstance<MockInteractionPublisher>().observe { it.removeInteractionListener(this) }
    attached.filterIsInstance<MockWarningPublisher>().observe { it.removeWarningListener(this) }
  }
}

internal fun <T : Any> Iterable<T>.distinctInstances(): List<T> {
  val seen = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
  return filter(seen::add)
}
