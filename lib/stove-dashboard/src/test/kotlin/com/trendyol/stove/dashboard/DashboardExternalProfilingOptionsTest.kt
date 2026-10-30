package com.trendyol.stove.dashboard

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DashboardExternalProfilingOptionsTest : FunSpec({
  test("invalid service identities fail before launching the application") {
    listOf("", " ", " app ", "app\tname", "app\u0000", "café", "a".repeat(257)).forEach { name ->
      shouldThrow<IllegalArgumentException> {
        DashboardExternalProfilingOptions("https://stove.example", serviceName = name)
      }
      shouldThrow<IllegalArgumentException> {
        DashboardSystemOptions(name, externalProfiling = DashboardExternalProfilingOptions("https://stove.example"))
      }
    }
  }

  test("an explicit profiling service can differ from the dashboard app name") {
    val options = DashboardSystemOptions(
      "café",
      externalProfiling = DashboardExternalProfilingOptions("https://stove.example/observe", serviceName = "cafe")
    )
    options.externalProfiling?.serviceName shouldBe "cafe"
  }

  test("invalid destinations, intervals and native types fail during configuration") {
    listOf("ftp://stove.example", "https://user:pass@stove.example", "https://stove.example?", "https://stove.example#").forEach { url ->
      shouldThrow<IllegalArgumentException> { DashboardExternalProfilingOptions(url) }
    }
    listOf(0, 3601).forEach { interval ->
      shouldThrow<IllegalArgumentException> { DashboardExternalProfilingOptions("https://stove.example", uploadIntervalSeconds = interval) }
    }
    listOf(emptyList(), listOf("cpu", "unknown")).forEach { types ->
      shouldThrow<IllegalArgumentException> { DashboardExternalProfilingOptions("https://stove.example", profileTypes = types) }
    }
  }
})
