package com.trendyol.stove.cucumberfixtures.probe

import com.trendyol.stove.extensions.ReportProbe
import com.trendyol.stove.extensions.cucumber.Given
import com.trendyol.stove.reporting.ReportEntry
import com.trendyol.stove.reporting.StoveTestContextHolder
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.StoveContext
import com.trendyol.stove.system.stove
import com.trendyol.stove.tracing.TraceContext
import io.cucumber.java.After
import io.cucumber.java.AfterAll
import io.cucumber.java.Before
import io.cucumber.java.BeforeAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

object Probe : ReportProbe() {
  @JvmStatic
  @BeforeAll
  fun start(): Unit = runBlocking {
    reset()
    startStove()
    Stove.reporter().addListener(Probe)
  }

  @JvmStatic
  @AfterAll
  fun stop() {
    assertClean()
    Stove.stop()
  }
}

class ProbeSteps {
  private var calls = 0

  @Given("a fresh scenario across a dispatcher switch")
  suspend fun fresh() {
    stove {
      calls++
      calls shouldBe 1
      val boundStove = currentCoroutineContext()[StoveContext].shouldNotBeNull().stove
      this.stove shouldBeSameInstanceAs boundStove
      val context = StoveTestContextHolder.get().shouldNotBeNull()
      val trace = TraceContext.current().shouldNotBeNull()
      withContext(Dispatchers.Default) {
        currentCoroutineContext()[StoveContext].shouldNotBeNull().stove shouldBeSameInstanceAs boundStove
        StoveTestContextHolder.get() shouldBe context
        TraceContext.current() shouldBe trace
        Stove.reporter().currentTestId() shouldBe context.testId
        assertHelperUses(boundStove)
        Stove.reporter().record(ReportEntry.success("Probe", context.testId, "dispatcher switch"))
      }
    }
  }

  private suspend fun assertHelperUses(expected: Stove) = stove {
    this.stove shouldBeSameInstanceAs expected
  }

  @Given("an assertion fails")
  suspend fun fail(): Unit = stove {
    Stove.reporter().record(ReportEntry.failure("Probe", Stove.reporter().currentTestId(), "assertion", "expected failure"))
    kotlinx.coroutines.delay(1)
    throw AssertionError("expected failure")
  }

  @Given("an ambiguous {word}")
  suspend fun ambiguousOne(word: String) {
    word shouldBe "step"
  }

  @Given("an {word} step")
  suspend fun ambiguousTwo(word: String) {
    word shouldBe "ambiguous"
  }

  @Given("a pending step")
  suspend fun pending(): Unit = throw io.cucumber.java.PendingException("pending implementation")

  @Before("@before-failure")
  fun beforeFailure(): Unit = throw IllegalStateException("before hook failed")

  @After("@after-failure")
  fun afterFailure(): Unit = throw IllegalStateException("after hook failed")
}
