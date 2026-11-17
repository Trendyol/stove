package com.trendyol.stove.dashboard

import arrow.core.*
import com.trendyol.stove.dashboard.api.*
import com.trendyol.stove.events.*
import com.trendyol.stove.events.RunComponent
import com.trendyol.stove.interactions.*
import com.trendyol.stove.reporting.*
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.tracing.SpanInfo
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Exports Stove observations through one subscription; survives application and system cleanup. */
class DashboardSystem(
  override val stove: Stove,
  private val options: DashboardSystemOptions
) : RunObserver,
  RunAware,
  ExposesConfiguration,
  ReportEventListener,
  SpanEventListener,
  MockInteractionListener,
  MockWarningListener {
  private val logger = LoggerFactory.getLogger(javaClass)
  private val lock = ReentrantLock()
  private val runId = stove.events.runId
  private var emitter: Option<DashboardEmitter> = None
  private var profiling: Option<AutoCloseable> = None
  private var startTime = Instant.now()
  private var accepting = false
  private val closing = AtomicBoolean(false)
  private var closed = false
  private var lifecycleFailed = false
  private val tests = DashboardTests(::emit)
  private val observations = DashboardObservations(stove.events, ::observe)

  override fun configuration(): List<String> = options.externalProfiling?.let {
    listOf(
      "stove.profiling.serverUrl=${it.serverUrl}",
      "stove.profiling.runId=$runId",
      "stove.profiling.serviceName=${it.serviceName ?: options.appName}",
      "stove.profiling.uploadIntervalSeconds=${it.uploadIntervalSeconds}",
      "stove.profiling.profileTypes=${it.profileTypes.joinToString(",")}"
    )
  } ?: emptyList()

  override suspend fun run() {
    if (closing.get()) return
    observations.awaitPending()
    if (lock.withLock { emitter.isSome() || closing.get() }) return
    start(stove.runComponents())
    stove.startObserving()
  }

  private fun observe(event: StoveEvent): Unit = lock.withLock {
    when (event) {
      is RunStarting -> start(event.components)
      is EnvironmentChanged -> if (accepting) emit { runEnvironment = event.components.toEnvironmentProto() }
      is RunFinished -> lifecycleFailed = event.failure.isSome()
      is TestStarted -> accept { tests.start(event.context) }
      is TestFailed -> accept { tests.fail(event.testId, event.error) }
      is TestEnded -> accept { tests.finish(event.testId) }
      is EntryRecorded -> accept { tests.record(event.entry) }
      is SnapshotRecorded -> accept { emit { snapshot = event.toProto() } }
      is SpanRecorded -> accept { emit { spanRecorded = event.span.toProto() } }
      is InteractionObserved -> accept { emit { mockInteraction = event.interaction.toProto() } }
      is WarningObserved -> accept { emit { mockWarning = event.warning.toProto() } }
    }
  }

  private fun start(components: List<RunComponent>): Unit = lock.withLock {
    if (emitter.isSome() || closed) return
    emitter = Some(DashboardEmitter(options.ingestion))
    startTime = Instant.now()
    emit {
      runStarted = RunStartedEvent.newBuilder().setTimestamp(startTime.toTimestamp())
        .setAppName(options.appName).addAllSystems(components.map { it.name })
        .putAllMetadata(options.metadata).setStoveVersion(StoveCompatibilityVersion.VALUE).build()
    }
    emit { runEnvironment = components.toEnvironmentProto() }
    accepting = true
    startProfiling()
  }

  private fun startProfiling() {
    if (!System.getProperty("stove.profiling.enabled").toBoolean()) return
    runCatching { DashboardProfiling(runId, options.appName) }
      .onSuccess { profiling = Some(it) }
      .onFailure { logger.warn("Stove profiling could not start; check the tracing plugin and platform support", it) }
  }

  override fun onTestStarted(ctx: StoveTestContext) = stove.publish(TestStarted(ctx))
  override fun onTestFailed(testId: String, error: String) = stove.publish(TestFailed(testId, error))
  override fun onTestEnded(testId: String) = stove.publish(TestEnded(testId))
  override fun onEntryRecorded(entry: ReportEntry) = stove.publish(EntryRecorded(entry))
  override fun onSpanRecorded(span: SpanInfo) = stove.publish(SpanRecorded(span))
  override fun onInteraction(interaction: MockInteraction) = stove.publish(InteractionObserved(interaction))
  override fun onWarning(warning: MockWarning) = stove.publish(WarningObserved(warning))

  private fun accept(action: () -> Unit) {
    if (accepting) action()
  }

  private fun emit(build: DashboardEventBuilder) {
    emitter.onSome { it.tryEmit(DashboardEvent.newBuilder().setRunId(runId).apply(build).build()) }
  }

  override suspend fun stop() = close()

  override fun close() {
    if (!closing.compareAndSet(false, true)) return
    stove.capturePendingSnapshots()
    observations.drain()
    lock.withLock {
      accepting = false
      closed = true
    }
    observations.close()
    runCatching { profiling.onSome { it.close() } }.onFailure { logger.warn("Stove profiling could not flush", it) }
    profiling = None
    tests.close()
    emit { runEnded = tests.result(Duration.between(startTime, Instant.now()).toMillis(), lifecycleFailed) }
    emitter.onSome { it.close() }
  }
}
