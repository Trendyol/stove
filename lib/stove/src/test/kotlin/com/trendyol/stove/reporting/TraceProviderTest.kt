package com.trendyol.stove.reporting

import arrow.core.None
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TraceProviderTest :
  FunSpec({
    test("default wait time should be 300ms") {
      val provider = CapturingTraceProvider()

      provider.getTraceVisualizationForCurrentTest()

      provider.lastWaitTime shouldBe 300L
    }

    test("custom wait time should be respected") {
      val provider = CapturingTraceProvider()

      provider.getTraceVisualizationForCurrentTest(1234)

      provider.lastWaitTime shouldBe 1234L
    }
  })

private class CapturingTraceProvider : TraceProvider {
  var lastWaitTime: Long? = null

  override fun getTraceVisualizationForCurrentTest(waitTimeMs: Long) =
    None.also { lastWaitTime = waitTimeMs }
}
