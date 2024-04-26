package com.trendyol.stove.testing.e2e.standalone.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.system.abstractions.ConfiguresExposedConfiguration
import com.trendyol.stove.testing.e2e.system.abstractions.SystemOptions

class KafkaSystemOptions(
  val containerOptions: KafkaContainerOptions = KafkaContainerOptions(),
  val errorTopicSuffixes: List<String> = listOf("error", "errorTopic", "retry", "retryTopic"),
  val bridgeGrpcServerPort: Int = 50051,
  val objectMapper: ObjectMapper = StoveObjectMapper.Default,
  override val configureExposedConfiguration: (KafkaExposedConfiguration) -> List<String> = { _ -> listOf() }
) : SystemOptions, ConfiguresExposedConfiguration<KafkaExposedConfiguration>
