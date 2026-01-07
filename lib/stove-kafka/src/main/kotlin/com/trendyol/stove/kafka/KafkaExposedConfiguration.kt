package com.trendyol.stove.kafka

import com.trendyol.stove.system.abstractions.ExposedConfiguration

data class KafkaExposedConfiguration(
  val bootstrapServers: String,
  val interceptorClass: String
) : ExposedConfiguration
