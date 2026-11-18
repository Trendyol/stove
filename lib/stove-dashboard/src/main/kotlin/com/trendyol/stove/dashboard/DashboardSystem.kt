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
  private sealed interface State {
    data object Waiting : State
    data object Closed : State
  }

  private var state: State = State.Waiting
  private val closing = AtomicBoolean(false)
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
    if (lock.withLock { state != State.Waiting || closing.get() }) return
    start(stove.runComponents(), Instant.now())
    stove.startObserving()
  }

  internal fun observe(envelope: EventEnvelope) {
    val event = envelope.event
    when (event) {
      is RunStarting -> start(event.components, envelope.timestamp)

      is SnapshotRecorded -> {
        val run = lock.withLock { state as? ActiveRun } ?: return
        val snapshot = event.toProto()
        lock.withLock { if (state === run) run.emit { this.snapshot = snapshot } }
      }

      else -> lock.withLock { (state as? ActiveRun)?.observe(event, envelope.timestamp) }
    }
  }

  private fun start(components: List<RunComponent>, started: Instant): Unit = lock.withLock {
    if (state == State.Waiting) state = ActiveRun(components, started)
  }

  private inner class ActiveRun(components: List<RunComponent>, private val started: Instant) : State, AutoCloseable {
    private val emitter = DashboardEmitter(options.ingestion)
    private val tests = DashboardTests(::emit)
    private var terminal: Option<Pair<Instant, Boolean>> = None

    init {
      emit {
        runStarted = RunStartedEvent.newBuilder().setTimestamp(started.toTimestamp())
          .setAppName(options.appName).addAllSystems(components.map { it.name })
          .putAllMetadata(options.metadata).setStoveVersion(StoveCompatibilityVersion.VALUE).build()
      }
      emit { runEnvironment = components.toEnvironmentProto() }
    }

    private val profiling = startProfiling()

    fun observe(event: StoveEvent, at: Instant) {
      when (event) {
        is EnvironmentChanged -> emit { runEnvironment = event.components.toEnvironmentProto() }
        is RunFinished -> terminal = Some(at to event.failure.isSome())
        is TestStarted -> tests.start(event.context, at)
        is TestFailed -> tests.fail(event.testId, event.error)
        is TestEnded -> tests.finish(event.testId, at)
        is EntryRecorded -> tests.record(event.entry)
        is SpanRecorded -> emit { spanRecorded = event.span.toProto() }
        is InteractionObserved -> emit { mockInteraction = event.interaction.toProto() }
        is WarningObserved -> emit { mockWarning = event.warning.toProto() }
      }
    }

    fun emit(build: DashboardEventBuilder) {
      emitter.tryEmit(DashboardEvent.newBuilder().setRunId(runId).apply(build).build())
    }

    override fun close() {
      runCatching { profiling.onSome { it.close() } }.onFailure { logger.warn("Stove profiling could not flush", it) }
      try {
        val (ended, lifecycleFailed) = terminal.getOrElse { Instant.now() to false }
        tests.close(ended)
        emit { runEnded = tests.result(ended, Duration.between(started, ended).toMillis(), lifecycleFailed) }
      } finally {
        emitter.close()
      }
    }
  }

  private fun startProfiling(): Option<AutoCloseable> {
    if (!System.getProperty("stove.profiling.enabled").toBoolean()) return None
    return runCatching { DashboardProfiling(runId, options.appName) }
      .onFailure { logger.warn("Stove profiling could not start; check the tracing plugin and platform support", it) }
      .fold({ Some(it) }, { None })
  }

  override fun onTestStarted(ctx: StoveTestContext) = stove.publish(TestStarted(ctx))
  override fun onTestFailed(testId: String, error: String) = stove.publish(TestFailed(testId, error))
  override fun onTestEnded(testId: String) = stove.publish(TestEnded(testId))
  override fun onEntryRecorded(entry: ReportEntry) = stove.publish(EntryRecorded(entry))
  override fun onSpanRecorded(span: SpanInfo) = stove.publish(SpanRecorded(span))
  override fun onInteraction(interaction: MockInteraction) = stove.publish(InteractionObserved(interaction))
  override fun onWarning(warning: MockWarning) = stove.publish(WarningObserved(warning))

  override suspend fun stop() = close()

  override fun close() {
    stove.requireExternalEventCaller()
    if (!closing.compareAndSet(false, true)) return
    try {
      stove.capturePendingSnapshots()
      observations.drain()
    } finally {
      val previous = lock.withLock { state.also { state = State.Closed } }
      observations.close()
      if (previous is ActiveRun) previous.close()
    }
  }
}
