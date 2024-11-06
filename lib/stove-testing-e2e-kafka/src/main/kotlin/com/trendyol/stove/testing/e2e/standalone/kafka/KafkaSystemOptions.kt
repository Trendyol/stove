package com.trendyol.stove.testing.e2e.standalone.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.system.abstractions.*
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serializer

class KafkaSystemOptions(
  /**
   * Suffixes for error and retry topics in the application.
   */
  val topicSuffixes: TopicSuffixes = TopicSuffixes(),
  /**
   * If true, the system will listen to the messages published by the Kafka system.
   */
  val listenPublishedMessagesFromStove: Boolean = false,
  /**
   * The port of the bridge gRPC server that is used to communicate with the Kafka system.
   */
  val bridgeGrpcServerPort: Int = stoveKafkaBridgePortDefault.toInt(),
  /**
   * The object mapper that is used to serialize and deserialize messages.
   */
  val objectMapper: ObjectMapper = stoveKafkaObjectMapperRef,
  /**
   * The Value serializer that is used to serialize messages.
   */
  val valueSerializer: Serializer<Any> = StoveKafkaValueSerializer(),
  /**
   * The options for the Kafka container.
   */
  val containerOptions: KafkaContainerOptions = KafkaContainerOptions(),
  /**
   * The options for the Kafka system that is exposed to the application
   */
  override val configureExposedConfiguration: (KafkaExposedConfiguration) -> List<String>
) : SystemOptions, ConfiguresExposedConfiguration<KafkaExposedConfiguration>

/**
 * Suffixes for error and retry topics in the application.
 * Stove Kafka uses these suffixes to understand the intent of the topic and the message.
 */
data class TopicSuffixes(
  val error: List<String> = listOf(".error", ".DLT"),
  val retry: List<String> = listOf(".retry")
) {
  fun isRetryTopic(topic: String): Boolean = retry.any { topic.endsWith(it, ignoreCase = true) }

  fun isErrorTopic(topic: String): Boolean = error.any { topic.endsWith(it, ignoreCase = true) }
}
