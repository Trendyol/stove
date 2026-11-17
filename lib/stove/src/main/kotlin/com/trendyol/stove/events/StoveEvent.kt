package com.trendyol.stove.events

import arrow.core.None
import arrow.core.Option
import com.trendyol.stove.interactions.MockInteraction
import com.trendyol.stove.interactions.MockWarning
import com.trendyol.stove.reporting.ReportEntry
import com.trendyol.stove.reporting.StoveTestContext
import com.trendyol.stove.reporting.SystemSnapshot
import com.trendyol.stove.system.abstractions.SystemKey
import com.trendyol.stove.tracing.SpanInfo
import java.time.Instant
import kotlin.time.Duration

/** Facts observed by Stove. Subscribers must not perform required setup or cleanup. */
interface StoveEvent

data class RunStarting(val components: List<RunComponent>) : StoveEvent
data class SystemStarted(
  val source: RunComponent,
  val duration: Duration,
  val systemType: Class<*>,
  val key: Option<SystemKey>
) : StoveEvent

/** Emitted after a system's close succeeds, including cleanup after a failed startup. */
data class SystemStopped(
  val source: RunComponent,
  val duration: Duration,
  val systemType: Class<*>,
  val key: Option<SystemKey>
) : StoveEvent

/** Emitted when a system lifecycle phase fails; cancellation is not a failure. */
data class SystemFailed(
  val source: RunComponent,
  val phase: PhaseName,
  val error: Throwable,
  val systemType: Class<*>,
  val key: Option<SystemKey>
) : StoveEvent
data object RunReady : StoveEvent
data class RunFinished(val failure: Option<String> = None) : StoveEvent
data class EnvironmentChanged(val components: List<RunComponent>) : StoveEvent
data class TestStarted(val context: StoveTestContext) : StoveEvent
data class TestFailed(val testId: String, val error: String) : StoveEvent
data class TestEnded(val testId: String) : StoveEvent
data class EntryRecorded(val entry: ReportEntry) : StoveEvent
data class SnapshotRecorded(
  val testId: String,
  val trigger: SnapshotTrigger,
  val snapshot: SystemSnapshot,
  val capturedAt: Instant = Instant.now()
) : StoveEvent
data class SpanRecorded(val span: SpanInfo) : StoveEvent
data class InteractionObserved(val interaction: MockInteraction) : StoveEvent
data class WarningObserved(val warning: MockWarning) : StoveEvent

data class EventEnvelope(
  val runId: String,
  val eventId: String,
  val sequence: Long,
  val timestamp: Instant,
  val event: StoveEvent
)

/** Registration metadata and observed phases; system configuration is not inspected. */
data class RunComponent(
  val id: String,
  val name: String,
  val type: String,
  val key: Option<String> = None,
  val role: ComponentRole = ComponentRole.SYSTEM,
  val phases: List<ComponentPhase> = emptyList()
)

data class ComponentPhase(
  val name: PhaseName,
  val status: PhaseStatus,
  val startedAt: Instant,
  val durationMs: Long = 0,
  val error: Option<String> = None
)

enum class SnapshotTrigger { FAILURE, TEST_END }

enum class ComponentRole { SYSTEM, APPLICATION }
enum class PhaseName { PREPARE, START, AFTER_START, BEFORE_STOP, STOP, CLOSE }
enum class PhaseStatus { RUNNING, SUCCEEDED, FAILED, CANCELLED }

internal fun Throwable.description(): String = message ?: javaClass.name
