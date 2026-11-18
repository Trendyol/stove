package com.trendyol.stove.events

import com.trendyol.stove.interactions.*
import com.trendyol.stove.reporting.*
import com.trendyol.stove.system.Stove
import com.trendyol.stove.tracing.SpanInfo
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Bridges existing observations without changing the callbacks that maintain test state. */
internal class StoveEventSources(private val stove: Stove, private val events: StoveEventBus) :
  PreCleanupReportListener, AutoCloseable {
  val snapshots = StoveSnapshots(stove, events::recordSnapshot)
  private val spans = Attachments(SpanListenerRegistry::addSpanListener, SpanListenerRegistry::removeSpanListener) { live ->
    object : SpanEventListener {
      override fun onSpanRecorded(span: SpanInfo) {
        if (live.get()) emit(SpanRecorded(span))
      }
    }
  }
  private val interactions =
    Attachments(MockInteractionPublisher::addInteractionListener, MockInteractionPublisher::removeInteractionListener) { live ->
      MockInteractionListener { if (live.get()) emit(InteractionObserved(it)) }
    }
  private val warnings = Attachments(MockWarningPublisher::addWarningListener, MockWarningPublisher::removeWarningListener) { live ->
    MockWarningListener { if (live.get()) emit(WarningObserved(it)) }
  }

  fun start() = stove.addReportListener(this)

  fun refresh() {
    val publishers = stove.allRegisteredSystems().distinctInstances()
    publishers.filterIsInstance<SpanListenerRegistry>().forEach(spans::attach)
    publishers.filterIsInstance<MockInteractionPublisher>().forEach(interactions::attach)
    publishers.filterIsInstance<MockWarningPublisher>().forEach(warnings::attach)
  }

  override fun onTestStarted(ctx: StoveTestContext) = emit(TestStarted(ctx))
  override fun onTestFailed(testId: String, error: String) = emit(TestFailed(testId, error))
  override fun onTestEnded(testId: String) = emit(TestEnded(testId))
  override fun onEntryRecorded(entry: ReportEntry) = emit(EntryRecorded(entry))
  private fun emit(event: StoveEvent) = events.publish(event, this)

  override fun close() {
    stove.removeReportListener(this)
    spans.close()
    interactions.close()
    warnings.close()
  }
}

private typealias ListenerAction<P, L> = (P, L) -> Unit

private class Attachments<P : Any, L>(
  private val add: ListenerAction<P, L>,
  private val remove: ListenerAction<P, L>,
  private val listener: (AtomicBoolean) -> L
) {
  private class Attempt<L>(val live: AtomicBoolean, val listener: L, var retry: Boolean = false)
  private val attempts = IdentityHashMap<P, Attempt<L>>()
  private val logger = LoggerFactory.getLogger(javaClass)

  fun attach(publisher: P) {
    attempts[publisher]?.let {
      if (!it.retry) return
      it.retry = false
      detach(publisher, it)
    }
    val live = AtomicBoolean(true)
    val attempt = Attempt(live, listener(live))
    attempts[publisher] = attempt
    runCatching { add(publisher, attempt.listener) }.onFailure {
      attempt.retry = true
      logger.warn("Stove observation attachment failed", it)
    }
  }

  private fun detach(publisher: P, attempt: Attempt<L>) {
    attempt.live.set(false)
    runCatching { remove(publisher, attempt.listener) }.onFailure { logger.warn("Stove observation removal failed", it) }
  }

  fun close() {
    attempts.forEach(::detach)
    attempts.clear()
  }
}

internal fun <T : Any> Iterable<T>.distinctInstances(): List<T> {
  val seen = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
  return filter(seen::add)
}
