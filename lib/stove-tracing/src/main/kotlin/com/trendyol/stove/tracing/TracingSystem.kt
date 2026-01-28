@file:Suppress("unused")

package com.trendyol.stove.tracing

import arrow.core.*
import com.trendyol.stove.reporting.*
import com.trendyol.stove.system.*
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl

@StoveDsl
class TracingSystem(
  override val stove: Stove,
  private val options: TracingSystemOptions
) : PluggedSystem,
  RunAware,
  TraceProvider {
  private val logger = org.slf4j.LoggerFactory.getLogger(TracingSystem::class.java)
  internal val collector: StoveTraceCollector = StoveTraceCollector()
  private var spanReceiver: OTLPSpanReceiver? = null

  override suspend fun run() {
    if (options.tracingOptions.spanReceiverEnabled) {
      startSpanReceiver()
    }
  }

  override suspend fun stop() {
    stopSpanReceiver()
    clearAllTraces()
  }

  override fun getTraceVisualizationForCurrentTest(waitTimeMs: Long): Option<TraceVisualization> {
    val ctx = TraceContext.current() ?: return None
    val spans = pollForSpans(ctx.traceId, waitTimeMs)
    return createVisualizationOrFallback(ctx.traceId, ctx.testId, spans)
  }

  @StoveDsl
  suspend fun ensureTraceStarted(): TraceContext =
    TraceContext.current() ?: startNewTrace()

  @StoveDsl
  fun endTrace() {
    TraceContext.clear()
  }

  @StoveDsl
  fun currentContext(): Option<TraceContext> =
    TraceContext.current().toOption()

  @StoveDsl
  fun validation(): Option<TraceValidationDsl> =
    currentContext().map { createValidation(it.traceId) }

  @StoveDsl
  fun validation(traceId: String): TraceValidationDsl =
    createValidation(traceId)

  @StoveDsl
  override fun then(): Stove = stove

  override fun close() {
    clearAllTraces()
  }

  // Private helper methods

  private fun startSpanReceiver() {
    spanReceiver = OTLPSpanReceiver(collector, options.tracingOptions.spanReceiverPort)
    spanReceiver?.start()?.fold(
      ifLeft = { error ->
        // Port binding failure is non-fatal - tests continue without span collection
        // This commonly happens when multiple test modules run in parallel on CI
        logger.warn(
          "[StoveTracing] Failed to start OTLP receiver on port {}: {}. " +
            "Tracing spans will not be collected. This is usually caused by another " +
            "test module already using this port.",
          options.tracingOptions.spanReceiverPort,
          error.message
        )
        spanReceiver = null
      },
      ifRight = { /* Started successfully */ }
    )
  }

  private fun stopSpanReceiver() {
    spanReceiver?.stop()
  }

  private fun clearAllTraces() {
    collector.clearAll()
    TraceContext.clear()
  }

  /**
   * Polls for spans with intelligent waiting strategy.
   * Returns as soon as first spans arrive, then waits briefly for stragglers.
   */
  private fun pollForSpans(traceId: String, maxWaitTimeMs: Long): List<SpanInfo> {
    val deadline = System.currentTimeMillis() + maxWaitTimeMs

    // Poll until we find spans or timeout
    val initialSpans = pollUntilSpansArrive(traceId, deadline)

    // If we found spans, wait a bit more for stragglers
    return if (initialSpans.isNotEmpty()) {
      waitForStragglersAndCollect(traceId, deadline)
    } else {
      emptyList()
    }
  }

  /**
   * Polls repeatedly until spans arrive or deadline is reached.
   */
  private fun pollUntilSpansArrive(traceId: String, deadline: Long): List<SpanInfo> {
    while (System.currentTimeMillis() < deadline) {
      val spans = collector.getTrace(traceId)
      if (spans.isNotEmpty()) return spans

      if (!sleepQuietly(TracingConstants.DEFAULT_SPAN_POLL_INTERVAL_MS)) {
        break // Interrupted
      }
    }
    return emptyList()
  }

  /**
   * Waits briefly for straggler spans, then collects final result.
   */
  private fun waitForStragglersAndCollect(traceId: String, deadline: Long): List<SpanInfo> {
    val remainingTime = deadline - System.currentTimeMillis()
    val stragglerWait = minOf(TracingConstants.STRAGGLER_WAIT_TIME_MS, remainingTime).coerceAtLeast(0)

    sleepQuietly(stragglerWait)
    return collector.getTrace(traceId)
  }

  /**
   * Sleeps quietly, handling interruption gracefully.
   * Returns true if sleep completed, false if interrupted.
   */
  private fun sleepQuietly(millis: Long): Boolean {
    if (millis <= 0) return true

    return try {
      Thread.sleep(millis)
      true
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      false
    }
  }

  /**
   * Creates visualization from spans, or falls back to most relevant trace if empty.
   */
  private fun createVisualizationOrFallback(
    traceId: String,
    testId: String,
    spans: List<SpanInfo>
  ): Option<TraceVisualization> = when {
    spans.isNotEmpty() -> TraceVisualization.from(traceId, testId, spans).some()
    else -> findMostRelevantTrace(testId)
  }

  /**
   * Finds the most relevant trace when the expected trace ID has no spans.
   * Uses the most recent trace (by start time) as a heuristic, as it's likely
   * to be the trace closest to the test execution.
   */
  private fun findMostRelevantTrace(testId: String): Option<TraceVisualization> {
    val allTraces = collector.getAllTraces()
    if (allTraces.isEmpty()) return None

    // Find the trace with the most recent span start time
    val (traceId, traceSpans) = allTraces
      .filter { it.value.isNotEmpty() }
      .maxByOrNull { entry -> entry.value.maxOf { it.startTimeNanos } }
      ?: return None

    return TraceVisualization.from(traceId, testId, traceSpans).some()
  }

  private suspend fun startNewTrace(): TraceContext {
    val testId = resolveTestId()
    val ctx = TraceContext.start(testId)
    collector.registerTrace(ctx.traceId, testId)
    return ctx
  }

  private suspend fun resolveTestId(): String =
    resolveKotestTestId()
      ?: resolveJUnitTestId()
      ?: generateFallbackTestId()

  private suspend fun resolveKotestTestId(): String? =
    currentStoveTestContext()?.testId

  private fun resolveJUnitTestId(): String? =
    StoveTestContextHolder.get()?.testId

  private fun generateFallbackTestId(): String =
    "stove-trace-${System.currentTimeMillis()}"

  private fun createValidation(traceId: String): TraceValidationDsl =
    TraceValidationDsl(collector, traceId)
}

data class TracingSystemOptions(
  val tracingOptions: TracingOptions = TracingOptions().enabled()
)

internal fun Stove.withTracing(options: TracingSystemOptions): Stove {
  this.getOrRegister(TracingSystem(this, options))
  return this
}

internal fun Stove.tracingSystem(): TracingSystem = getOrNone<TracingSystem>().getOrElse {
  throw SystemNotRegisteredException(TracingSystem::class)
}

@StoveDsl
fun WithDsl.tracing(configure: @StoveDsl TracingOptions.() -> Unit = {}): Stove =
  this.stove.withTracing(createTracingOptions(configure))

private fun createTracingOptions(configure: TracingOptions.() -> Unit): TracingSystemOptions {
  val options = TracingOptions()
  options.configure()
  options.enabled()
  return TracingSystemOptions(options)
}

/**
 * DSL scope for tracing validation - exposes trace context and all validation methods directly.
 */
@StoveDsl
class TracingValidationScope(
  val ctx: TraceContext,
  private val validation: TraceValidationDsl,
  val collector: StoveTraceCollector
) {
  val traceId: String get() = ctx.traceId
  val rootSpanId: String get() = ctx.rootSpanId
  val testId: String get() = ctx.testId

  fun toTraceparent(): String = ctx.toTraceparent()

  // Validation method delegates
  fun shouldContainSpan(operationName: String) = validation.shouldContainSpan(operationName)

  fun shouldContainSpanMatching(predicate: (SpanInfo) -> Boolean) = validation.shouldContainSpanMatching(predicate)

  fun shouldNotContainSpan(operationName: String) = validation.shouldNotContainSpan(operationName)

  fun shouldNotHaveFailedSpans() = validation.shouldNotHaveFailedSpans()

  fun shouldHaveFailedSpan(operationName: String) = validation.shouldHaveFailedSpan(operationName)

  fun executionTimeShouldBeLessThan(duration: kotlin.time.Duration) = validation.executionTimeShouldBeLessThan(duration)

  fun executionTimeShouldBeGreaterThan(duration: kotlin.time.Duration) = validation.executionTimeShouldBeGreaterThan(duration)

  fun spanCountShouldBe(expected: Int) = validation.spanCountShouldBe(expected)

  fun spanCountShouldBeAtLeast(minimum: Int) = validation.spanCountShouldBeAtLeast(minimum)

  fun spanCountShouldBeAtMost(maximum: Int) = validation.spanCountShouldBeAtMost(maximum)

  fun shouldHaveSpanWithAttribute(key: String, value: String) = validation.shouldHaveSpanWithAttribute(key, value)

  fun shouldHaveSpanWithAttributeContaining(key: String, substring: String) =
    validation.shouldHaveSpanWithAttributeContaining(key, substring)

  // Query methods
  fun getSpanCount(): Int = validation.getSpanCount()

  fun getFailedSpans(): List<SpanInfo> = validation.getFailedSpans()

  fun getFailedSpanCount(): Int = validation.getFailedSpanCount()

  fun findSpan(predicate: (SpanInfo) -> Boolean): Option<SpanInfo> = validation.findSpan(predicate).toOption()

  fun findSpanByName(operationName: String): Option<SpanInfo> = validation.findSpanByName(operationName).toOption()

  fun spanTree(): Option<SpanNode> = validation.spanTree().toOption()

  fun getTotalDuration(): kotlin.time.Duration = validation.getTotalDuration()

  fun renderTree(): String = validation.renderTree()

  fun renderSummary(): String = validation.renderSummary()

  /**
   * Waits for at least the expected number of spans to be collected.
   * Useful for ensuring spans have been exported before making assertions.
   */
  fun waitForSpans(expectedCount: Int, timeoutMs: Long = 2000): List<SpanInfo> =
    collector.waitForSpans(traceId, expectedCount, timeoutMs)

  /**
   * Gets a visualization of the trace for this test.
   * Includes all spans for this trace ID formatted as a tree.
   */
  fun getTraceVisualization(): TraceVisualization {
    val spans = collector.getTrace(traceId)
    return TraceVisualization.from(traceId, testId, spans)
  }

  /**
   * Gets visualizations for ALL collected traces (not just this test's trace ID).
   * Useful for seeing all activity during the test execution.
   */
  fun getAllTraceVisualizations(): List<TraceVisualization> {
    val allTraces = collector.getAllTraces()
    return allTraces.map { (traceId, spans) ->
      TraceVisualization.from(traceId, testId, spans)
    }
  }
}

@StoveDsl
suspend fun ValidationDsl.tracing(
  validation: @StoveDsl suspend TracingValidationScope.() -> Unit
) {
  val system = this.stove.tracingSystem()
  val ctx = system.ensureTraceStarted()
  val scope = createTracingValidationScope(system, ctx)
  validation(scope)
}

private fun createTracingValidationScope(
  system: TracingSystem,
  ctx: TraceContext
): TracingValidationScope {
  val traceValidation = system.validation(ctx.traceId)
  return TracingValidationScope(ctx, traceValidation, system.collector)
}

@StoveDsl
fun ValidationDsl.tracingSystem(): TracingSystem = this.stove.tracingSystem()
