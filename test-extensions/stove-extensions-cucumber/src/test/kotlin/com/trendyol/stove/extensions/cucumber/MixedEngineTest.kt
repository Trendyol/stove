package com.trendyol.stove.extensions.cucumber

import com.trendyol.stove.cucumberfixtures.probe.Probe
import com.trendyol.stove.extensions.ReportProbe
import com.trendyol.stove.extensions.junit.StoveJUnitExtension
import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.reporting.ReportEntry
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathResource
import org.junit.platform.launcher.EngineFilter.includeEngines
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener

class MixedEngineTest : FunSpec({
  test("one launch runs Cucumber, Jupiter and Kotest exactly once with separate report sessions") {
    mixedReports.reset()
    val request = LauncherDiscoveryRequestBuilder.request()
      .selectors(
        selectClasspathResource("probe/lifecycle.feature"),
        selectClass(MixedJupiterFixture::class.java),
        selectClass(MixedKotestFixture::class.java)
      )
      .filters(includeEngines("cucumber", "junit-jupiter", "kotest"))
      .configurationParameters(cucumberConfiguration)
      .configurationParameter("cucumber.filter.tags", "@passing")
      .build()
    val summary = SummaryGeneratingListener()
    LauncherFactory.create().execute(request, summary)

    summary.summary.failures.map { it.exception.stackTraceToString() } shouldBe emptyList()
    summary.summary.testsFoundCount shouldBe 10
    summary.summary.testsStartedCount shouldBe 4
    summary.summary.testsSkippedCount shouldBe 6
    summary.summary.testsSucceededCount shouldBe 4
    Probe.assertBalanced(2)
    mixedReports.assertBalanced(2)
    mixedReports.entries.size shouldBe 2
    ReportProbe.assertClean()
  }
})

private val mixedReports = ReportProbe()

private suspend fun startMixedStove() {
  ReportProbe.startStove()
  Stove.reporter().addListener(mixedReports)
}

private fun stopMixedStove() {
  try {
    ReportProbe.assertClean()
  } finally {
    Stove.stop()
  }
}

private suspend fun exerciseMixedStyle() = stove {
  delay(1)
  val context = ReportProbe.assertActive()
  Stove.reporter().record(ReportEntry.success("Mixed engines", context.testId, "assertion"))
}

class MixedKotestFixture : FunSpec({
  extensions(StoveKotestExtension())
  beforeSpec { startMixedStove() }
  afterSpec { stopMixedStove() }
  test("Kotest scenario") { exerciseMixedStyle() }
})

@ExtendWith(StoveJUnitExtension::class)
class MixedJupiterFixture {
  companion object {
    @JvmStatic
    @BeforeAll
    fun start(): Unit = runBlocking { startMixedStove() }

    @JvmStatic
    @AfterAll
    fun stop() = stopMixedStove()
  }

  @Test
  suspend fun scenario() = exerciseMixedStyle()
}
