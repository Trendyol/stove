@file:Suppress("TooManyFunctions")

package com.trendyol.stove.logging

import com.trendyol.stove.reporting.FailureReportContributor
import com.trendyol.stove.reporting.StoveMdc
import com.trendyol.stove.reporting.StoveTestContextHolder
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.PluggedSystem
import com.trendyol.stove.system.abstractions.RunAware
import com.trendyol.stove.tracing.TraceContext
import io.opentelemetry.api.baggage.Baggage
import io.opentelemetry.api.trace.Span
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

internal class LoggingSystem(
  override val stove: Stove,
  private val options: LoggingSystemOptions
) : PluggedSystem,
  RunAware,
  LogListenerRegistry,
  FailureReportContributor,
  StoveLogSink {
  private val listeners = CopyOnWriteArrayList<LogEventListener>()
  private val queue = ArrayBlockingQueue<Signal>(options.queueCapacity.coerceAtLeast(1))
  private val running = AtomicBoolean(false)
  private val reentrant = ThreadLocal.withInitial { false }
  private val droppedSinceLastMarker = AtomicLong(0)
  private val recentByTest = ConcurrentHashMap<String, ArrayDeque<StoveLogRecord>>()
  private val includePatterns = options.includeLoggerPatterns.map(::Regex)
  private val excludePatterns = options.excludeLoggerPatterns.map(::Regex)
  private var worker: Thread? = null

  override suspend fun run() {
    running.set(true)
    stove.addFailureReportContributor(this)
    worker = thread(name = "stove-log-drain", isDaemon = true) { drainLoop() }
    LogbackInstaller.install(this)
    Log4j2Installer.install(this)
  }

  override suspend fun stop() {
    close()
  }

  override fun close() {
    LogbackInstaller.uninstall()
    Log4j2Installer.uninstall()
    running.set(false)
    queue.offer(Signal.Stop)
    worker?.join(3_000)
    stove.removeFailureReportContributor(this)
  }

  override fun addLogListener(listener: LogEventListener) {
    listeners.add(listener)
  }

  override fun removeLogListener(listener: LogEventListener) {
    listeners.remove(listener)
  }

  override fun capture(log: CapturedLog) {
    if (!running.get() || reentrant.get() || !shouldCapture(log)) return

    reentrant.set(true)
    try {
      val record = toRecord(log)
      if (!queue.offer(Signal.Record(record))) {
        val dropped = droppedSinceLastMarker.incrementAndGet()
        if (dropped == 1L || dropped % DROP_MARKER_INTERVAL == 0L) {
          emitDropped(
            LogsDropped(
              timestamp = Instant.now(),
              testId = record.testId,
              traceId = record.traceId,
              droppedCount = dropped,
              reason = "queue_overflow"
            )
          )
        }
      }
    } finally {
      reentrant.set(false)
    }
  }

  override fun contribute(testId: String): String {
    val logs = recentByTest[testId]
      ?.let { synchronized(it) { it.toList() } }
      ?.filter { it.severityNumber >= options.failureReportMinLevel.severityNumber }
      ?.takeLast(FAILURE_REPORT_LOG_LIMIT)
      ?: emptyList()

    if (logs.isEmpty()) return ""

    return buildString {
      appendLine("═══════════════════════════════════════════════════════════════")
      appendLine("LOGS (${options.failureReportMinLevel.name}+)")
      appendLine("═══════════════════════════════════════════════════════════════")
      logs.forEach { log ->
        append(log.timestamp)
        append(" ")
        append(log.severityText)
        append(" ")
        append(log.logger)
        append(" - ")
        appendLine(log.body)
        log.exceptionMessage?.let { appendLine("  ${log.exceptionType ?: "Exception"}: $it") }
      }
    }.trimEnd()
  }

  private fun drainLoop() {
    while (running.get() || queue.isNotEmpty()) {
      when (val signal = queue.poll() ?: continue) {
        is Signal.Record -> emitRecord(signal.record)
        Signal.Stop -> return
      }
    }
  }

  private fun emitRecord(record: StoveLogRecord) {
    val dropped = droppedSinceLastMarker.getAndSet(0)
    if (dropped > 0) {
      emitDropped(
        LogsDropped(
          timestamp = Instant.now(),
          testId = record.testId,
          traceId = record.traceId,
          droppedCount = dropped,
          reason = "queue_overflow"
        )
      )
    }
    record.testId?.let { addRecent(it, record) }
    listeners.forEach { listener -> runCatching { listener.onLogRecorded(record) } }
  }

  private fun emitDropped(event: LogsDropped) {
    listeners.forEach { listener -> runCatching { listener.onLogsDropped(event) } }
  }

  private fun addRecent(testId: String, record: StoveLogRecord) {
    val buffer = recentByTest.computeIfAbsent(testId) { ArrayDeque() }
    synchronized(buffer) {
      while (buffer.size >= options.maxRecordsPerTest) {
        buffer.removeFirst()
      }
      buffer.addLast(record)
    }
  }

  private fun shouldCapture(log: CapturedLog): Boolean =
    log.level.severityNumber >= options.minLevel.severityNumber &&
      excludePatterns.none { it.matches(log.logger) } &&
      (includePatterns.isEmpty() || includePatterns.any { it.matches(log.logger) })

  private fun toRecord(log: CapturedLog): StoveLogRecord {
    val correlation = resolveCorrelation(log.mdc)
    val (message, messageTruncated) = truncate(redactMessage(log.message), options.maxMessageLength)
    val (stackTrace, stackTruncated) = truncate(
      log.throwableStackTrace?.let(::redactMessage),
      options.maxStackTraceLength
    )
    return StoveLogRecord(
      timestamp = log.timestamp,
      observedTimestamp = Instant.now(),
      severityText = log.level.name,
      severityNumber = log.level.severityNumber,
      logger = log.logger,
      thread = log.thread,
      body = message.orEmpty(),
      exceptionType = log.throwableType,
      exceptionMessage = log.throwableMessage?.let(::redactMessage),
      exceptionStackTrace = stackTrace,
      attributes = redactAttributes(log.mdc),
      traceId = correlation.traceId,
      spanId = correlation.spanId,
      testId = correlation.testId,
      correlationSource = correlation.source,
      source = log.source,
      truncated = messageTruncated || stackTruncated
    )
  }

  private fun resolveCorrelation(mdc: Map<String, String>): Correlation {
    val spanContext = runCatching { Span.current().spanContext }.getOrNull()
    if (spanContext?.isValid == true) {
      return Correlation(
        traceId = spanContext.traceId,
        spanId = spanContext.spanId,
        testId = Baggage.current().getEntryValue(TraceContext.BAGGAGE_TEST_ID_KEY)
          ?: mdc.firstValue(StoveMdc.TEST_ID_KEY),
        source = LogCorrelationSource.OTEL_CONTEXT
      )
    }

    val mdcTraceId = mdc.firstValue("trace_id", "traceId", "trace.id")
    val mdcSpanId = mdc.firstValue("span_id", "spanId", "span.id")
    val mdcTestId = mdc.firstValue(StoveMdc.TEST_ID_KEY, TraceContext.BAGGAGE_TEST_ID_KEY, "test_id", "testId")
    if (mdcTraceId != null || mdcSpanId != null || mdcTestId != null) {
      return Correlation(mdcTraceId, mdcSpanId, mdcTestId, LogCorrelationSource.MDC)
    }

    TraceContext.current()?.let { ctx ->
      return Correlation(ctx.traceId, ctx.rootSpanId, ctx.testId, LogCorrelationSource.TRACE_CONTEXT)
    }

    StoveTestContextHolder.get()?.let { ctx ->
      return Correlation(null, null, ctx.testId, LogCorrelationSource.STOVE_TEST_CONTEXT)
    }

    return Correlation(null, null, null, LogCorrelationSource.UNASSIGNED)
  }

  private fun redactAttributes(attributes: Map<String, String>): Map<String, String> =
    attributes.mapValues { (key, value) ->
      if (options.redactionEnabled && options.sensitiveKeySubstrings.any { key.contains(it, ignoreCase = true) }) {
        REDACTED
      } else {
        redactMessage(value)
      }
    }

  private fun redactMessage(value: String): String {
    if (!options.redactionEnabled) return value
    return SECRET_PATTERNS.fold(value) { acc, pattern ->
      pattern.replace(acc) { match -> "${match.groupValues[1]}=$REDACTED" }
    }
  }

  private fun truncate(value: String?, limit: Int): Pair<String?, Boolean> {
    if (value == null || value.length <= limit) return value to false
    return value.take(limit) + "...<truncated ${value.length - limit} chars>" to true
  }

  private fun Map<String, String>.firstValue(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> this[key]?.takeIf(String::isNotBlank) }

  private data class Correlation(
    val traceId: String?,
    val spanId: String?,
    val testId: String?,
    val source: LogCorrelationSource
  )

  private sealed interface Signal {
    data class Record(val record: StoveLogRecord) : Signal
    data object Stop : Signal
  }

  companion object {
    private const val DROP_MARKER_INTERVAL = 1_000L
    private const val FAILURE_REPORT_LOG_LIMIT = 50
    private const val REDACTED = "[REDACTED]"
    private val SECRET_PATTERNS = listOf(
      Regex("(?i)(authorization|password|secret|token|apiKey|api_key|cookie)\\s*[:=]\\s*\\S+")
    )
  }
}
