package com.trendyol.stove.cucumberfixtures.parallel

import com.trendyol.stove.extensions.ReportProbe
import com.trendyol.stove.extensions.cucumber.Given
import com.trendyol.stove.extensions.cucumber.StoveCucumberPlugin
import com.trendyol.stove.reporting.ReportEntry
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.StoveContext
import com.trendyol.stove.system.stove
import com.trendyol.stove.tracing.TraceContext
import io.cucumber.java.After
import io.cucumber.java.AfterAll
import io.cucumber.java.BeforeAll
import io.cucumber.plugin.ConcurrentEventListener
import io.cucumber.plugin.event.EventPublisher
import io.cucumber.plugin.event.TestCaseFinished
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

object ParallelProbe : ReportProbe() {
  // Every pair must overlap; a serial implementation fails at the bounded barrier.
  val overlap = CyclicBarrier(2)
  val traces = ConcurrentHashMap<String, String>()
  val cleanupWorkers = ConcurrentHashMap<String, String>()

  @JvmStatic
  @BeforeAll
  fun start(): Unit = runBlocking {
    reset()
    overlap.reset()
    traces.clear()
    cleanupWorkers.clear()
    startStove()
    Stove.reporter().addListener(ParallelProbe)
  }

  @JvmStatic
  @AfterAll
  fun stop() {
    try {
      assertClean()
    } finally {
      Stove.stop()
    }
  }
}

class ParallelSteps {
  private var scenarioId: String? = null
  private var bodyCompleted = false

  @Given("concurrent scenario {word} completes as {word}")
  suspend fun concurrent(key: String, outcome: String) = stove {
    scenarioId shouldBe null
    val context = ReportProbe.assertActive()
    scenarioId = context.testId
    val trace = TraceContext.current().shouldNotBeNull()
    val boundStove = currentCoroutineContext()[StoveContext].shouldNotBeNull().stove
    ParallelProbe.traces[context.testId] = trace.traceId
    Stove.reporter().currentTest().entries() shouldBe emptyList()

    withContext(Dispatchers.Default) {
      ParallelProbe.overlap.await(10, TimeUnit.SECONDS)
      coroutineScope {
        async {
          ReportProbe.assertActive() shouldBe context
          TraceContext.current() shouldBe trace
          currentCoroutineContext()[StoveContext].shouldNotBeNull().stove shouldBe boundStove
          Stove.reporter().record(ReportEntry.success("Parallel", context.testId, "marker:$key"))
        }.await()
      }
    }
    ReportProbe.assertActive() shouldBe context
    TraceContext.current() shouldBe trace
    bodyCompleted = true
    if (outcome != "pass") {
      Stove.reporter().record(ReportEntry.failure("Parallel", context.testId, "marker:$key", "$key $outcome"))
      if (outcome == "cancel") throw CancellationException("$key $outcome")
      throw AssertionError("$key $outcome")
    }
  }

  @io.cucumber.java.en.Then("the preceding suspend step has finished")
  fun nextStep() {
    bodyCompleted shouldBe true
    ReportProbe.assertActive().testId shouldBe scenarioId
  }

  @After
  fun after() {
    val context = ReportProbe.assertActive()
    context.testId shouldBe scenarioId
    TraceContext.current().shouldNotBeNull().testId shouldBe context.testId
    Stove.reporter().currentTest().entries().all { it.testId == context.testId } shouldBe true
  }
}

/** Registered after Stove so it observes cleanup on the actual Cucumber worker, before reuse. */
class WorkerCleanupPlugin : ConcurrentEventListener {
  override fun setEventPublisher(publisher: EventPublisher) {
    publisher.registerHandlerFor(TestCaseFinished::class.java) { event ->
      ReportProbe.assertClean()
      shouldThrow<IllegalStateException> { StoveCucumberPlugin.current() }
      ParallelProbe.cleanupWorkers[event.testCase.name] = Thread.currentThread().name
    }
  }
}
