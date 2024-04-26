package com.trendyol.stove.testing.e2e.standalone.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.system.abstractions.*

class KafkaSystemOptions(
  val containerOptions: KafkaContainerOptions = KafkaContainerOptions(),
  val errorTopicSuffixes: List<String> = listOf("error", "errorTopic", "retry", "retryTopic"),
  val bridgeGrpcServerPort: Int = STOVE_KAFKA_BRIDGE_PORT_DEFAULT.toInt(),
  val objectMapper: ObjectMapper = stoveKafkaObjectMapperRef,
  override val configureExposedConfiguration: (KafkaExposedConfiguration) -> List<String> = { _ -> listOf() }
) : SystemOptions, ConfiguresExposedConfiguration<KafkaExposedConfiguration>
