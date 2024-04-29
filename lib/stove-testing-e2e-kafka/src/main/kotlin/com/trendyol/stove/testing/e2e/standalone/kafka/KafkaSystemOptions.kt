package com.trendyol.stove.testing.e2e.standalone.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.system.abstractions.*

class KafkaSystemOptions(
  val topicSuffixes: TopicSuffixes = TopicSuffixes(error = listOf("error"), retry = listOf("retry")),
  val bridgeGrpcServerPort: Int = STOVE_KAFKA_BRIDGE_PORT_DEFAULT.toInt(),
  val objectMapper: ObjectMapper = stoveKafkaObjectMapperRef,
  val containerOptions: KafkaContainerOptions = KafkaContainerOptions(),
  override val configureExposedConfiguration: (KafkaExposedConfiguration) -> List<String> = { _ -> listOf() }
) : SystemOptions, ConfiguresExposedConfiguration<KafkaExposedConfiguration>

/**
 * Suffixes for error and retry topics in the application.
 * Stove Kafka uses these suffixes to understand the intent of the topic and the message.
 */
data class TopicSuffixes(
  val error: List<String>,
  val retry: List<String>
) {
  fun isRetryTopic(topic: String): Boolean = retry.any { topic.endsWith(it) }

  fun isErrorTopic(topic: String): Boolean = error.any { topic.endsWith(it) }
}
