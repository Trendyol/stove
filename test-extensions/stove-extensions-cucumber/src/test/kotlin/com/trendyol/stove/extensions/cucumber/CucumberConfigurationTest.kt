package com.trendyol.stove.extensions.cucumber

import com.trendyol.stove.cucumberfixtures.probe.Probe
import com.trendyol.stove.extensions.ReportProbe
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.concurrency.TestExecutionMode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.testkit.engine.EngineExecutionResults

class CucumberConfigurationTest : FunSpec({
  testExecutionMode = TestExecutionMode.Sequential

  listOf("regular", "privatefun", "extension", "staticfun", "inherited").forEach { variant ->
    test("rejects $variant step definitions during glue loading") {
      Probe.reset()
      val result = cucumberEngine("probe/configuration.feature")
        .configurationParameter("cucumber.glue", "com.trendyol.stove.cucumberfixtures.$variant")
        .execute()
      result.failuresText() shouldContain if (variant == "inherited") "cannot be inherited" else "public suspend member"
      Probe.assertBalanced(0)
    }
  }

  test("native annotations on suspend steps expose the continuation argument mismatch") {
    val result = cucumberEngine("probe/configuration.feature")
      .configurationParameter("cucumber.glue", "$HOOKS,$PROBE,com.trendyol.stove.cucumberfixtures.nativeannotation")
      .execute()
    result.failuresText() shouldContain "Continuation"
    result.testEvents().assertStatistics { it.started(1).failed(1) }
    Probe.assertBalanced(1)
  }

  test("missing plugin gives actionable registration guidance and leaves no active session") {
    val result = cucumberEngine("probe/lifecycle.feature")
      .configurationParameter("cucumber.filter.tags", "@passing")
      .configurationParameter("cucumber.plugin", "summary")
      .execute()
    result.failuresText() shouldContain "Register com.trendyol.stove.extensions.cucumber.StoveCucumberPlugin"
    result.testEvents().assertStatistics { it.started(2).failed(2) }
    Probe.assertBalanced(0)
    ReportProbe.assertClean()
  }

  test("missing step glue reports undefined steps and closes the scenario") {
    Probe.start()
    try {
      val result = cucumberEngine("probe/configuration.feature")
        .configurationParameter("cucumber.glue", HOOKS)
        .execute()
      result.testEvents().assertStatistics { it.started(1).failed(1) }
      result.failuresText() shouldContain "UndefinedStepException"
      Probe.assertBalanced(1)
    } finally {
      Probe.stop()
    }
  }

  test("missing diagnostic glue keeps failures visible on stderr and closes the session") {
    val run = cucumberEngine("probe/lifecycle.feature")
      .configurationParameter("cucumber.glue", PROBE)
      .configurationParameter("cucumber.filter.name", "^Assertion failure$")
      .withDiagnostics()
    run.result.testEvents().assertStatistics { it.started(1).failed(1) }
    run.attachments shouldBe emptyList()
    run.stderr shouldContain "expected failure"
    run.stderr shouldContain "STOVE TEST EXECUTION REPORT"
    Probe.assertBalanced(1)
  }

  test("a failure after attachment preserves both diagnostics and still closes the session") {
    val run = cucumberEngine("probe/lifecycle.feature")
      .configurationParameter("cucumber.glue", "$PROBE,com.trendyol.stove.cucumberfixtures.latehook")
      .configurationParameter("cucumber.filter.name", "^Assertion failure$")
      .withDiagnostics()
    run.result.testEvents().assertStatistics { it.started(1).failed(1) }
    run.attachments.single() shouldContain "expected failure"
    run.stderr shouldContain "late teardown failure"
    run.stderr.contains('\u001B') shouldBe false
    Probe.assertBalanced(1)
  }

  test("a tag filter matching nothing opens no scenario sessions") {
    Probe.reset()
    val result = cucumberEngine("probe/lifecycle.feature")
      .configurationParameter("cucumber.filter.tags", "@does-not-exist")
      .execute()
    result.testEvents().assertStatistics { it.started(0) }
    Probe.assertBalanced(0)
  }

  test("dry runs discover steps without executing their bodies") {
    Probe.reset()
    val result = cucumberEngine("probe/lifecycle.feature")
      .configurationParameter("cucumber.filter.tags", "@passing")
      .configurationParameter("cucumber.execution.dry-run", "true")
      .execute()
    result.testEvents().assertStatistics { it.started(2).succeeded(2) }
    Probe.entries.size shouldBe 0
    Probe.assertBalanced(2)
    ReportProbe.assertClean()
  }

  test("startup failures remain run failures and do not open scenario sessions") {
    Probe.reset()
    val result = cucumberEngine("probe/configuration.feature")
      .configurationParameter("cucumber.glue", "$HOOKS,com.trendyol.stove.cucumberfixtures.startup")
      .execute()
    result.failuresText() shouldContain "application startup failed"
    result.testEvents().assertStatistics { it.started(0) }
    Probe.assertBalanced(0)
  }
})

private const val HOOKS = "com.trendyol.stove.extensions.cucumber"
private const val PROBE = "com.trendyol.stove.cucumberfixtures.probe"

internal fun EngineExecutionResults.failuresText(): String = allEvents().failed().list()
  .joinToString("\n") { event ->
    event.getRequiredPayload(TestExecutionResult::class.java).throwable.orElseThrow().stackTraceToString()
  }
