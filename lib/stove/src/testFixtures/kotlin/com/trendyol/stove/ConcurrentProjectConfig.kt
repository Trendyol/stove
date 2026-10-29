package com.trendyol.stove

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.engine.concurrency.SpecExecutionMode
import io.kotest.engine.concurrency.TestExecutionMode

/** Opt audited suites into concurrency without duplicating the budget allocation. */
abstract class ConcurrentProjectConfig(
  concurrentSpecs: Boolean = true,
  concurrentTests: Boolean = false
) : AbstractProjectConfig() {
  private val specSlots = if (concurrentSpecs) testSpecConcurrency() else 1

  override val specExecutionMode = if (concurrentSpecs) {
    SpecExecutionMode.LimitedConcurrency(specSlots)
  } else {
    SpecExecutionMode.Sequential
  }

  override val testExecutionMode = if (concurrentTests) {
    TestExecutionMode.LimitedConcurrency(testCaseConcurrency(parallelSpecs = specSlots))
  } else {
    TestExecutionMode.Sequential
  }
}
