package com.trendyol.stove.extensions

import com.trendyol.stove.reporting.*
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.tracing.TraceContext
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Observes real adapter events; assertions run outside callbacks, whose errors the reporter catches. */
open class ReportProbe(private val accepts: (StoveTestContext) -> Boolean = { true }) : ReportEventListener {
  val started = CopyOnWriteArrayList<StoveTestContext>()
  val ended = CopyOnWriteArrayList<String>()
  val failures = ConcurrentHashMap<String, String>()
  val entries = CopyOnWriteArrayList<ReportEntry>()
  private val reports = CopyOnWriteArrayList<TestReport>()
  private val initialEntries = CopyOnWriteArrayList<List<ReportEntry>>()

  override fun onTestStarted(ctx: StoveTestContext) {
    if (!accepts(ctx)) return
    started += ctx
    val report = Stove.reporter().currentTest()
    reports += report
    initialEntries += report.entries()
  }

  override fun onTestEnded(testId: String) {
    if (started.any { it.testId == testId }) ended += testId
  }

  override fun onTestFailed(testId: String, error: String) {
    if (started.any { it.testId == testId }) failures[testId] = error
  }

  override fun onEntryRecorded(entry: ReportEntry) {
    if (started.any { it.testId == entry.testId }) entries += entry
  }

  fun reset() {
    started.clear()
    ended.clear()
    failures.clear()
    entries.clear()
    reports.clear()
    initialEntries.clear()
  }

  fun assertBalanced(count: Int) {
    started shouldHaveSize count
    started.map { it.testId }.distinct() shouldHaveSize count
    ended shouldHaveSize count
    ended.sorted() shouldBe started.map { it.testId }.sorted()
    initialEntries.all { it.isEmpty() } shouldBe true
    reports.all { it.entries().isEmpty() } shouldBe true
  }

  companion object {
    suspend fun startStove() {
      Stove().apply {
        applicationUnderTest(object : ApplicationUnderTest<Unit> {
          override suspend fun start(configurations: List<String>) = Unit
          override suspend fun stop() = Unit
        })
      }.run()
    }

    fun assertActive(path: List<String>? = null): StoveTestContext {
      val context = StoveTestContextHolder.get().shouldNotBeNull()
      if (path != null) context.testPath shouldBe path
      Stove.reporter().currentTestId() shouldBe context.testId
      TraceContext.current().shouldNotBeNull()
      return context
    }

    fun assertClean() {
      StoveTestContextHolder.get() shouldBe null
      TraceContext.current() shouldBe null
      Stove.reporter().currentTestIdOrNull() shouldBe null
      Stove.reporter().currentTestOrNull() shouldBe null
    }
  }
}
