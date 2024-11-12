package com.stove.spring.streams.example.e2e

import com.google.protobuf.Message
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG
import io.confluent.kafka.streams.serdes.protobuf.KafkaProtobufSerde
import okio.ByteString.Companion.toByteString
import org.slf4j.LoggerFactory
import java.util.*

class TestHelper {
  companion object {
    private val logger = LoggerFactory.getLogger(TestHelper::class.java)
  }

  fun createConfiguredSerdeForRecordValues(): KafkaProtobufSerde<Message> {
    val schemaRegistryClient = MockSchemaRegistry.getClientForScope("mock-registry")
    val serde: KafkaProtobufSerde<Message> = KafkaProtobufSerde<Message>(schemaRegistryClient)
    val serdeConfig: MutableMap<String, Any?> = HashMap()
    serdeConfig[SCHEMA_REGISTRY_URL_CONFIG] = "mock://mock-registry"
    serde.configure(serdeConfig, false)
    return serde
  }

  fun getMessageFromBase64(message: Any, protobufSerde: KafkaProtobufSerde<Message>): Message? = try {
    protobufSerde
      .deserializer()
      .deserialize(
        "any",
        Base64
          .getDecoder()
          .decode(message.toString())
          .toByteString()
          .toByteArray()
      )
  } catch (_: Exception) {
    null
  }

  fun assertMessage(
    message: Message,
    name: String,
    condition: () -> Boolean
  ): Boolean {
    try {
      return when (message.descriptorForType.name) {
        name -> condition()
        else -> false
      }
    } catch (e: Exception) {
      logger.error(e.message, e)
    }
    return false
  }
}
