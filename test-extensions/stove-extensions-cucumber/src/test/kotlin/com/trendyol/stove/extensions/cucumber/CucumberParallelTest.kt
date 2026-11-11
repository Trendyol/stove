package com.trendyol.stove.extensions.cucumber

import com.trendyol.stove.cucumberfixtures.parallel.ParallelProbe
import com.trendyol.stove.cucumberfixtures.parallel.WorkerCleanupPlugin
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class CucumberParallelTest : FunSpec({
  listOf("parallel", "lambdaparallel").forEach { style ->
    test("$style: overlapping outline rows and features isolate reports, traces, failures and reused workers") {
      val run = cucumberEngine("parallel/first.feature", "parallel/second.feature")
        .configurationParameter("cucumber.glue", "com.trendyol.stove.extensions.cucumber,com.trendyol.stove.cucumberfixtures.$style")
        .withDiagnostics(WorkerCleanupPlugin::class.java.name)

      run.result.testEvents().assertStatistics { it.started(8).succeeded(4).failed(4) }
      run.result.containerEvents().failed().list() shouldBe emptyList()
      ParallelProbe.assertBalanced(8)
      ParallelProbe.started.map { it.specName }.distinct() shouldHaveSize 2
      ParallelProbe.traces.values.toSet() shouldHaveSize 8
      ParallelProbe.cleanupWorkers.size shouldBe 8
      ParallelProbe.cleanupWorkers.values.toSet() shouldHaveSize 2
      ParallelProbe.failures.values.toSet() shouldBe setOf("beta fail", "gamma cancel", "epsilon fail", "theta cancel")

      val keys = ParallelProbe.started.associate { it.testId to it.testName.removePrefix("Concurrent ") }
      ParallelProbe.entries shouldHaveSize 12
      ParallelProbe.entries.forEach { entry -> entry.action shouldBe "marker:${keys.getValue(entry.testId)}" }
      run.attachments shouldHaveSize 4
      run.attachments.forEach { attachment ->
        val key = attachment.substringBefore('\n').substringBefore(" (").removePrefix("Concurrent ")
        attachment shouldContain "marker:$key"
        keys.values.filter { it != key }.forEach { other -> attachment.contains("marker:$other") shouldBe false }
      }
      run.stderr shouldBe ""
    }
  }
})
