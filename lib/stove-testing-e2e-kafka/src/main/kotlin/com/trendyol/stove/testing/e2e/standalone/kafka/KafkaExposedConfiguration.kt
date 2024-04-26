package com.trendyol.stove.testing.e2e.standalone.kafka

import com.trendyol.stove.testing.e2e.system.abstractions.ExposedConfiguration

data class KafkaExposedConfiguration(
  val bootstrapServers: String
) : ExposedConfiguration
