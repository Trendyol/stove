package com.trendyol.stove.cucumberfixtures.lambdas

import com.trendyol.stove.cucumberfixtures.probe.Probe
import com.trendyol.stove.cucumberfixtures.probe.ProbeSteps
import com.trendyol.stove.cucumberfixtures.probe.SuspendingSteps
import com.trendyol.stove.extensions.ReportProbe
import com.trendyol.stove.extensions.cucumber.StoveSteps
import com.trendyol.stove.system.StoveContext
import com.trendyol.stove.system.ValidationDsl
import com.trendyol.stove.tracing.TraceContext
import io.cucumber.datatable.DataTable
import io.cucumber.java.AfterAll
import io.cucumber.java.BeforeAll
import io.cucumber.java.ParameterType
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object LambdaEnvironment {
  @JvmStatic @BeforeAll
  fun start() = Probe.start()

  @JvmStatic @AfterAll
  fun stop() = Probe.stop()
}

/** Reuse the same behavior and features to compare both registration styles. */
class LambdaSteps : StoveSteps() {
  private val lifecycle = ProbeSteps()
  private val suspending = SuspendingSteps()

  init {
    Given("a fresh scenario across a dispatcher switch") {
      assertReceiverContext()
      lifecycle.fresh()
    }
    When("an assertion fails") { lifecycle.fail() }
    Then<String>("an ambiguous {word}") { lifecycle.ambiguousOne(it) }
    And<String>("an {word} step") { lifecycle.ambiguousTwo(it) }
    But("a pending step") { lifecycle.pending() }
    Before("@before-failure", io.cucumber.java8.HookNoArgsBody { lifecycle.beforeFailure() })
    After("@after-failure", io.cucumber.java8.HookNoArgsBody { lifecycle.afterFailure() })

    Then<Int, String, SuspendingSteps.Colour>("a suspend step receives {int}, {string} and {colour}") { number, text, colour ->
      assertReceiverContext()
      suspending.arguments(number, text, colour)
    }
    And<DataTable>("a suspend step receives a table") { suspending.table(it.transpose().asLists().map { row -> row.map { cell -> requireNotNull(cell) } }) }
    And<String>("a suspend step receives a docstring") { suspending.docstring(it) }
    Given("a suspend child fails") { suspending.childFails() }
    Given("a suspend step is cancelled") { suspending.cancelled() }
  }

  @ParameterType("red|blue")
  fun colour(value: String): SuspendingSteps.Colour = suspending.colour(value)

  @io.cucumber.java.en.Given("a synchronous Java-backend step")
  fun nativeStep() = suspending.nativeStep()
}

internal suspend fun ValidationDsl.assertReceiverContext() {
  val scenario = ReportProbe.assertActive()
  val trace = TraceContext.current().shouldNotBeNull()
  withContext(Dispatchers.Default) {
    delay(1)
    currentCoroutineContext()[StoveContext].shouldNotBeNull().stove shouldBe stove
    ReportProbe.assertActive() shouldBe scenario
    TraceContext.current() shouldBe trace
  }
}
