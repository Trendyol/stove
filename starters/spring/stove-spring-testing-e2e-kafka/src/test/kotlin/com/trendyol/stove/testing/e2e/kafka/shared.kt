package com.trendyol.stove.testing.e2e.kafka

import io.kotest.common.ExperimentalKotest
import io.kotest.core.config.AbstractProjectConfig

class Setup : AbstractProjectConfig() {
  @ExperimentalKotest
  override val concurrentSpecs: Int = 1
}

class StoveBusinessException(message: String) : Exception(message)
