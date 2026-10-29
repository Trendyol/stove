package com.trendyol.stove

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.engine.concurrency.SpecExecutionMode
import io.kotest.engine.concurrency.TestExecutionMode

class KotestConfig : AbstractProjectConfig() {
  override val specExecutionMode = SpecExecutionMode.LimitedConcurrency(testSpecConcurrency())

  // Specs opt in individually once their test fixtures are independent.
  override val testExecutionMode = TestExecutionMode.Sequential
}
