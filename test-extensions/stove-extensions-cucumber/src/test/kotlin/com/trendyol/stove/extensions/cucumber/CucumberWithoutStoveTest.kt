package com.trendyol.stove.extensions.cucumber

import com.trendyol.stove.cucumberfixtures.probe.Probe
import com.trendyol.stove.system.Stove
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Runs in its own test JVM so earlier framework tests cannot hide missing-startup failures. */
class CucumberWithoutStoveTest : FunSpec({
  beforeTest { Stove.instanceInitialized() shouldBe false }
  afterTest { Stove.instanceInitialized() shouldBe false }

  for (parallel in listOf(false, true)) {
    test("dry run skips startup, steps and sessions with parallel=$parallel") {
      Probe.reset()
      val result = cucumberEngine("probe/lifecycle.feature")
        .configurationParameter("cucumber.filter.tags", "@passing")
        .configurationParameter("cucumber.execution.dry-run", "true")
        .configurationParameter("cucumber.execution.parallel.enabled", parallel.toString())
        .execute()
      result.testEvents().assertStatistics { it.started(2).succeeded(2) }
      Probe.entries.size shouldBe 0
      Probe.assertBalanced(0)
    }
  }

  for (hooks in listOf("", "com.trendyol.stove.extensions.cucumber,")) {
    test("real execution without startup fails with guidance, hooks=$hooks") {
      val result = cucumberEngine("probe/configuration.feature")
        .configurationParameter("cucumber.glue", "${hooks}com.trendyol.stove.cucumberfixtures.nostartup")
        .execute()
      result.testEvents().assertStatistics { it.started(1).failed(1) }
      result.failuresText() shouldContain "Start Stove in a Cucumber @BeforeAll hook before running scenarios"
    }
  }
})
