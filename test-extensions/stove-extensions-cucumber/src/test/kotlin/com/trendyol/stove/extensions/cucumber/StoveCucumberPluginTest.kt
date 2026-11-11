package com.trendyol.stove.extensions.cucumber

import com.trendyol.stove.cucumberfixtures.probe.Probe
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.concurrency.TestExecutionMode
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class StoveCucumberPluginTest : FunSpec({
  testExecutionMode = TestExecutionMode.Sequential
  listOf("probe", "lambdas").forEach { style ->
    listOf(false, true).forEach { parallel ->
      context("$style, parallel=$parallel") {
        test("real engine preserves outlines, failures, attachments and coroutine context") {
          val run = cucumberEngine("probe/lifecycle.feature")
            .configurationParameter("cucumber.execution.parallel.enabled", parallel.toString())
            .configurationParameter("cucumber.glue", "com.trendyol.stove.extensions.cucumber,com.trendyol.stove.cucumberfixtures.$style")
            .withDiagnostics()
          run.result.testEvents().assertStatistics { it.started(8).succeeded(2).failed(6) }
          Probe.assertBalanced(8)
          Probe.failures.size shouldBe 6
          Probe.entries.count { it.action == "dispatcher switch" } shouldBe 3
          run.json shouldContain "Stove diagnostics"
          run.json shouldContain "expected failure"
          run.json shouldContain "before hook failed"
          run.json shouldContain "after hook failed"
          run.attachments shouldHaveSize 6
          run.attachments.any { "STOVE TEST EXECUTION REPORT" in it } shouldBe true
          run.attachments.none { '\u001B' in it } shouldBe true
        }

        test("suspend steps reuse native argument conversion and propagate child failures and cancellation") {
          val result = cucumberEngine("probe/suspending.feature")
            .configurationParameter("cucumber.execution.parallel.enabled", parallel.toString())
            .configurationParameter("cucumber.glue", "com.trendyol.stove.extensions.cucumber,com.trendyol.stove.cucumberfixtures.$style")
            .execute()

          result.testEvents().assertStatistics { it.started(4).succeeded(2).failed(2) }
          Probe.assertBalanced(4)
          Probe.failures.values.toSet() shouldBe setOf("suspend child failed", "suspend step cancelled")
        }

        test("Cucumber tag filtering selects only the outline rows") {
          val result = cucumberEngine("probe/lifecycle.feature")
            .configurationParameter("cucumber.execution.parallel.enabled", parallel.toString())
            .configurationParameter("cucumber.glue", "com.trendyol.stove.extensions.cucumber,com.trendyol.stove.cucumberfixtures.$style")
            .configurationParameter("cucumber.filter.tags", "@passing")
            .execute()
          result.testEvents().assertStatistics { it.started(2).succeeded(2) }
          Probe.assertBalanced(2)
          Probe.failures.size shouldBe 0
        }
      }
    }
  }
  test("lambda overloads preserve argument order and support explicit table conversion") {
    val run = cucumberEngine("probe/lambda-arguments.feature")
      .configurationParameter("cucumber.glue", "com.trendyol.stove.extensions.cucumber,com.trendyol.stove.cucumberfixtures.lambdas")
      .withDiagnostics()
    run.result.testEvents().assertStatistics { it.started(2).succeeded(2) }
    run.json shouldContain "ArgumentSteps.kt:"
    Probe.assertBalanced(2)
  }
})
