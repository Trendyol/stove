package com.trendyol.stove.extensions

import com.trendyol.stove.reporting.ReportEventListener
import com.trendyol.stove.reporting.StoveTestContext
import com.trendyol.stove.reporting.StoveTestContextHolder
import com.trendyol.stove.system.Stove
import com.trendyol.stove.tracing.TraceContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.opentelemetry.api.baggage.Baggage
import io.opentelemetry.api.trace.Span
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

class StoveTestSessionTest : FunSpec({
  val reports = ReportProbe()

  beforeSpec {
    ReportProbe.startStove()
    Stove.reporter().addListener(reports)
  }
  beforeTest { reports.reset() }
  afterSpec { Stove.stop() }

  for (existingTrace in listOf(false, true)) {
    for (outcome in listOf("success", "failure", "cancellation")) {
      test("run restores worker traces after $outcome, existing trace: $existingTrace") {
        SwitchingDispatcher().use { dispatcher ->
          // Start each worker's own scope before the session, not by inheriting the session trace.
          val workerTraces = mutableMapOf<Thread, TraceContext?>()
          dispatcher.onWorkers {
            workerTraces[Thread.currentThread()] = if (existingTrace) TraceContext.start("previous") else null
          }
          val expectedFailure = when (outcome) {
            "failure" -> IllegalStateException("expected failure")
            "cancellation" -> CancellationException("expected cancellation")
            else -> null
          }
          val result = runCatching {
            withContext(dispatcher) {
              StoveTestSession.run(StoveTestContext("session", "session")) {
                ReportProbe.assertActive()
                val trace = TraceContext.current().shouldNotBeNull()
                val openingThread = Thread.currentThread()
                yield()
                Thread.currentThread() shouldNotBe openingThread
                ReportProbe.assertActive()
                TraceContext.current() shouldBe trace
                Span.current().spanContext.traceId shouldBe trace.traceId
                Baggage.current().getEntryValue(TraceContext.BAGGAGE_TEST_ID_KEY) shouldBe "session"
                expectedFailure?.let { throw it }
                "result"
              }
            }
          }
          result.exceptionOrNull() shouldBe expectedFailure
          if (expectedFailure == null) result.getOrThrow() shouldBe "result"
          reports.assertBalanced(1)
          dispatcher.onWorkers {
            val previous = workerTraces[Thread.currentThread()]
            StoveTestContextHolder.get() shouldBe null
            TraceContext.current() shouldBe previous
            Span.current().spanContext.isValid shouldBe (previous != null)
            if (previous != null) Span.current().spanContext.traceId shouldBe previous.traceId
            Baggage.current().getEntryValue(TraceContext.BAGGAGE_TEST_ID_KEY) shouldBe previous?.testId
          }
        }
      }
    }
  }

  test("open and use keep the trace active until close on the owning thread") {
    SwitchingDispatcher().use { dispatcher ->
      dispatcher.onWorkers {
        StoveTestSession.open(StoveTestContext("sync-${Thread.currentThread()}", "sync")).use {
          ReportProbe.assertActive()
          Span.current().spanContext.isValid shouldBe true
        }
        ReportProbe.assertClean()
        Span.current().spanContext.isValid shouldBe false
        Baggage.current().getEntryValue(TraceContext.BAGGAGE_TEST_ID_KEY) shouldBe null
      }
      reports.assertBalanced(2)
    }
  }

  test("run closes the session when cancelled before entering the block") {
    val job = Job()
    val listener = object : ReportEventListener {
      override fun onTestStarted(ctx: StoveTestContext) {
        job.cancel()
      }
    }
    Stove.reporter().addListener(listener)
    try {
      runCatching {
        withContext(job) {
          StoveTestSession.run(StoveTestContext("cancelled", "cancelled")) {
            error("cancelled block must not run")
          }
        }
      }.exceptionOrNull().shouldBeInstanceOf<CancellationException>()
      reports.assertBalanced(1)
      ReportProbe.assertClean()
    } finally {
      Stove.reporter().removeListener(listener)
      job.cancel()
    }
  }
})

/** Forces the first dispatch onto one worker and every resumption onto the other. */
private class SwitchingDispatcher : CoroutineDispatcher(), AutoCloseable {
  private val workers = List(2) { Executors.newSingleThreadExecutor() }
  private val dispatches = AtomicInteger()

  init {
    onWorkers { TraceContext.clear() }
  }

  override fun dispatch(context: CoroutineContext, block: Runnable) {
    workers[minOf(dispatches.getAndIncrement(), 1)].execute(block)
  }

  fun onWorkers(block: () -> Unit) {
    workers.forEach { it.submit(block).get(10, TimeUnit.SECONDS) }
  }

  override fun close() {
    try {
      onWorkers { TraceContext.clear() }
    } finally {
      workers.forEach { it.shutdownNow() }
    }
  }
}
