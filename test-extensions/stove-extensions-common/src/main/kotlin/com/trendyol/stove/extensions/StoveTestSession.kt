package com.trendyol.stove.extensions

import com.trendyol.stove.reporting.StoveTestContext
import com.trendyol.stove.reporting.StoveTestContextHolder
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.StoveContext
import com.trendyol.stove.tracing.TraceContext
import com.trendyol.stove.tracing.TraceReportBuilder
import com.trendyol.stove.tracing.TraceReportBuilder.shouldEnrichFailures
import kotlinx.coroutines.withContext

/** Framework-neutral reporting lifecycle. Open and close on the owning test thread. */
class StoveTestSession private constructor(val context: StoveTestContext) : AutoCloseable {
  private val stoveContext = StoveContext.capture()
  private val previousContext = StoveTestContextHolder.get()
  private var closed = false
  private val traceContext: TraceContext

  init {
    StoveTestContextHolder.set(context)
    Stove.reporter().startTest(context)
    traceContext = TraceContext.start(context.testId)
  }

  /** Carries the bound Stove instance, scenario identity, and trace across suspending operations. */
  suspend fun <T> withContext(block: suspend () -> T): T = withContext(context + stoveContext) {
    TraceContext.withPropagation(traceContext, block)
  }

  override fun close() {
    if (closed) return
    closed = true
    try {
      Stove.reporter().endTest()
    } finally {
      Stove.reporter().clear(context.testId)
      TraceContext.clear()
      previousContext?.let(StoveTestContextHolder::set) ?: StoveTestContextHolder.clear()
    }
  }

  companion object {
    fun open(context: StoveTestContext): StoveTestSession = StoveTestSession(context)

    /** Records failure independently of whether diagnostic enrichment is enabled. */
    fun reportFailure(message: String) = Stove.reporter().reportFailure(message)

    fun failureMessage(cause: Throwable): String =
      cause.message ?: cause::class.simpleName ?: TraceReportBuilder.DEFAULT_ERROR_MESSAGE

    fun failureReport(): String =
      if (Stove.options().shouldEnrichFailures()) TraceReportBuilder.buildFullReport() else ""

    suspend fun <T> run(context: StoveTestContext, block: suspend () -> T): T = withContext(context) {
      open(context).use { session -> session.withContext(block) }
    }
  }
}
