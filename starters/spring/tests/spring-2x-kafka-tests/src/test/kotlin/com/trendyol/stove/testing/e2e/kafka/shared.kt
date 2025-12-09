package com.trendyol.stove.testing.e2e.kafka

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.engine.concurrency.SpecExecutionMode

class Setup : AbstractProjectConfig() {
  override val specExecutionMode: SpecExecutionMode = SpecExecutionMode.Sequential
}

class StoveBusinessException(
  message: String
) : Exception(message)
