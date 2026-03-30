package com.trendyol.stove.portal

import arrow.core.getOrElse
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.Timestamp
import com.trendyol.stove.portal.api.EntryRecordedEvent
import com.trendyol.stove.portal.api.PortalEvent
import com.trendyol.stove.portal.api.RunEndedEvent
import com.trendyol.stove.portal.api.RunStartedEvent
import com.trendyol.stove.portal.api.SpanRecordedEvent
import com.trendyol.stove.portal.api.TestEndedEvent
import com.trendyol.stove.portal.api.TestStartedEvent
import com.trendyol.stove.reporting.ReportEntry
import com.trendyol.stove.reporting.ReportEventListener
import com.trendyol.stove.reporting.Reports
import com.trendyol.stove.reporting.SpanEventListener
import com.trendyol.stove.reporting.SpanListenerRegistry
import com.trendyol.stove.reporting.StoveTestContext
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.PluggedSystem
import com.trendyol.stove.system.abstractions.RunAware
import com.trendyol.stove.tracing.SpanInfo
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Portal system that streams test events to the stove CLI via gRPC.
 *
 * Add to your Stove config:
 * ```kotlin
 * Stove { }.with {
 *   portal { PortalSystemOptions(appName = "my-api") }
 * }
 * ```
 */
class PortalSystem(
  override val stove: Stove,
  private val options: PortalSystemOptions
) : PluggedSystem,
  RunAware,
  ReportEventListener,
  SpanEventListener {

  private val logger = org.slf4j.LoggerFactory.getLogger(PortalSystem::class.java)
  private val jsonMapper = ObjectMapper()
  private val runId = UUID.randomUUID().toString()
  private lateinit var emitter: PortalEmitter
  private var startTime: Instant = Instant.now()
  private var totalTests = 0
  private var passedTests = 0
  private var failedTests = 0
  private val lifecycleLock = ReentrantLock()
  private val testStartTimes = ConcurrentHashMap<String, Instant>()
  private val testFailures = ConcurrentHashMap<String, String>()

  override suspend fun run() {
    emitter = PortalEmitter(options.cliHost, options.cliPort)
    stove.reporter.addListener(this)
    registerSpanListener()
    startTime = Instant.now()
    emitter.tryEmit(
      portalEvent {
        runStarted = RunStartedEvent.newBuilder()
          .setTimestamp(now())
          .setAppName(options.appName)
          .addAllSystems(stove.activeSystems.values.filterIsInstance<Reports>().map { it.reportSystemName })
          .build()
      }
    )
  }

  override suspend fun stop() {
    close()
  }

  override fun onTestStarted(ctx: StoveTestContext) {
    totalTests++
    testStartTimes[ctx.testId] = Instant.now()
    emitter.tryEmit(
      portalEvent {
        testStarted = TestStartedEvent.newBuilder()
          .setTestId(ctx.testId)
          .setTestName(ctx.testName)
          .setSpecName(ctx.specName ?: "")
          .setTimestamp(now())
          .build()
      }
    )
  }

  override fun onTestFailed(testId: String, error: String) {
    testFailures[testId] = error
  }

  override fun onTestEnded(testId: String) {
    lifecycleLock.withLock {
      finishTestIfOpen(testId)
    }
  }

  override fun onEntryRecorded(entry: ReportEntry) {
    emitter.tryEmit(
      portalEvent {
        entryRecorded = EntryRecordedEvent.newBuilder()
          .setTestId(entry.testId)
          .setTimestamp(now())
          .setSystem(entry.system)
          .setAction(entry.action)
          .setResult(entry.result.name)
          .setInput(entry.input.getOrElse { "" }.toString())
          .setOutput(entry.output.getOrElse { "" }.toString())
          .putAllMetadata(entry.metadata.mapValues { it.value.toString() })
          .setExpected(entry.expected.getOrElse { "" }.toString())
          .setActual(entry.actual.getOrElse { "" }.toString())
          .setError(entry.error.getOrElse { "" })
          .setTraceId(entry.traceId.getOrElse { "" })
          .build()
      }
    )
    if (entry.isFailed) {
      testFailures.putIfAbsent(entry.testId, entry.error.getOrElse { "Assertion failed" })
    }
  }

  override fun onSpanRecorded(span: SpanInfo) {
    emitter.tryEmit(
      portalEvent {
        spanRecorded = SpanRecordedEvent.newBuilder()
          .setTraceId(span.traceId)
          .setSpanId(span.spanId)
          .setParentSpanId(span.parentSpanId ?: "")
          .setOperationName(span.operationName)
          .setServiceName(span.serviceName)
          .setStartTimeNanos(span.startTimeNanos)
          .setEndTimeNanos(span.endTimeNanos)
          .setStatus(span.status.name)
          .putAllAttributes(span.attributes)
          .apply {
            span.exception?.let { ex ->
              exception = com.trendyol.stove.portal.api.ExceptionInfo.newBuilder()
                .setType(ex.type)
                .setMessage(ex.message)
                .addAllStackTrace(ex.stackTrace)
                .build()
            }
          }
          .build()
      }
    )
  }

  override fun close() {
    lifecycleLock.withLock {
      if (!::emitter.isInitialized) return
      finalizeOpenTests()
      val duration = Duration.between(startTime, Instant.now()).toMillis()
      emitter.tryEmit(
        portalEvent {
          runEnded = RunEndedEvent.newBuilder()
            .setTimestamp(now())
            .setTotalTests(totalTests)
            .setPassed(passedTests)
            .setFailed(failedTests)
            .setDurationMs(duration)
            .build()
        }
      )
      stove.reporter.removeListener(this)
      emitter.close()
    }
  }

  private fun finalizeOpenTests() {
    val stillRunning = testStartTimes.keys.toList()
    stillRunning.forEach { testId ->
      logger.debug("Finalizing still-running test {} during portal shutdown", testId)
      finishTestIfOpen(testId)
    }
  }

  private fun finishTestIfOpen(testId: String) {
    val startedAt = testStartTimes.remove(testId) ?: run {
      logger.debug("Ignoring duplicate or late test end for {}", testId)
      return
    }

    emitSnapshots(testId)
    val durationMs = Duration.between(startedAt, Instant.now()).toMillis()
    val failure = testFailures.remove(testId)
    val status = if (failure != null) "FAILED" else "PASSED"
    emitter.tryEmit(
      portalEvent {
        testEnded = TestEndedEvent.newBuilder()
          .setTestId(testId)
          .setStatus(status)
          .setDurationMs(durationMs)
          .setError(failure ?: "")
          .setTimestamp(now())
          .build()
      }
    )
    if (failure != null) {
      failedTests++
    } else {
      passedTests++
    }
  }

  private fun emitSnapshots(testId: String) {
    stove.activeSystems.values
      .filterIsInstance<Reports>()
      .forEach { system ->
        runCatching { system.snapshot() }
          .onFailure { e ->
            logger.warn("Failed to collect snapshot from ${system.reportSystemName}: ${e.message}")
          }
          .onSuccess { snap ->
            val stateJson = runCatching { jsonMapper.writeValueAsString(snap.state) }
              .getOrDefault("{}")
            emitter.tryEmit(
              portalEvent {
                snapshot = com.trendyol.stove.portal.api.SnapshotEvent.newBuilder()
                  .setTestId(testId)
                  .setSystem(snap.system)
                  .setStateJson(stateJson)
                  .setSummary(snap.summary)
                  .build()
              }
            )
          }
      }
  }

  private fun registerSpanListener() {
    stove.activeSystems.values
      .filterIsInstance<SpanListenerRegistry>()
      .firstOrNull()
      ?.addSpanListener(this)
  }

  private fun portalEvent(block: PortalEvent.Builder.() -> Unit): PortalEvent =
    PortalEvent.newBuilder()
      .setRunId(runId)
      .apply(block)
      .build()

  private fun now(): Timestamp {
    val instant = Instant.now()
    return Timestamp.newBuilder()
      .setSeconds(instant.epochSecond)
      .setNanos(instant.nano)
      .build()
  }
}
