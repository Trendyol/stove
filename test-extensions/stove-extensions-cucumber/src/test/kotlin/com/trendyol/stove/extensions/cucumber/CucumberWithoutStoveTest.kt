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

  for (style in listOf("probe", "lambdas")) {
    for (parallel in listOf(false, true)) {
      test("$style: dry run skips startup, steps and sessions with parallel=$parallel") {
        Probe.reset()
        val result = cucumberEngine("probe/lifecycle.feature")
          .configurationParameter("cucumber.glue", "com.trendyol.stove.extensions.cucumber,com.trendyol.stove.cucumberfixtures.$style")
          .configurationParameter("cucumber.filter.tags", "@passing")
          .configurationParameter("cucumber.execution.dry-run", "true")
          .configurationParameter("cucumber.execution.parallel.enabled", parallel.toString())
          .execute()
        result.testEvents().assertStatistics { it.started(2).succeeded(2) }
        Probe.entries.size shouldBe 0
        Probe.assertBalanced(0)
      }
    }
  }

  for (style in listOf("nostartup", "lambdanostartup")) {
    for (hooks in listOf("", "com.trendyol.stove.extensions.cucumber,")) {
      test("$style: real execution without startup fails with guidance, hooks=$hooks") {
        val result = cucumberEngine("probe/configuration.feature")
          .configurationParameter("cucumber.glue", "${hooks}com.trendyol.stove.cucumberfixtures.$style")
          .execute()
        result.testEvents().assertStatistics { it.started(1).failed(1) }
        result.failuresText() shouldContain "Start Stove in a Cucumber @BeforeAll hook before running scenarios"
      }
    }
  }
})
