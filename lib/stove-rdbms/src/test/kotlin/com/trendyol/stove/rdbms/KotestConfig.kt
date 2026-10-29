package com.trendyol.stove.rdbms

import com.trendyol.stove.testCaseConcurrency
import com.trendyol.stove.testSpecConcurrency
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.engine.concurrency.SpecExecutionMode
import io.kotest.engine.concurrency.TestExecutionMode

class KotestConfig : AbstractProjectConfig() {
  // Each test owns its Stove instance and a uniquely named in-memory database.
  override val specExecutionMode = SpecExecutionMode.LimitedConcurrency(testSpecConcurrency())
  override val testExecutionMode = TestExecutionMode.LimitedConcurrency(testCaseConcurrency())
}
