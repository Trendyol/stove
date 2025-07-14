package com.trendyol.stove.testing.e2e.standalone.kafka

import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.system.abstractions.*
import org.apache.kafka.common.serialization.Serializer

class KafkaSystemOptions(
  /**
   * When set to `true`, an embedded Kafka broker is automatically started and used for the test run.
   * This is ideal for self-contained integration tests without external dependencies.
   * When `false`, the system will attempt to connect to an TestContainer Kafka instance.
   *
   * The default is `false`.
   */
  val useEmbeddedKafka: Boolean = false,
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
   * The Serde that is used while asserting the messages,
   * serializing while bridging the messages. Take a look at the [serde] property for more information.
   *
   * The default value is [StoveSerde.jackson]'s anyByteArraySerde.
   * Depending on your application's needs you might want to change this value.
   *
   * The places where it was used listed below:
   *
   * @see [com.trendyol.stove.testing.e2e.standalone.kafka.intercepting.StoveKafkaBridge] for bridging the messages.
   * @see StoveKafkaValueSerializer for serializing the messages.
   * @see StoveKafkaValueDeserializer for deserializing the messages.
   * @see valueSerializer for serializing the messages.
   */
  val serde: StoveSerde<Any, ByteArray> = stoveSerdeRef,
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
) : SystemOptions,
  ConfiguresExposedConfiguration<KafkaExposedConfiguration>

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
