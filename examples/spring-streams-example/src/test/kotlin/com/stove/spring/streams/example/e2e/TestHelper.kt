package com.stove.spring.streams.example.e2e

import arrow.core.*
import com.google.protobuf.Message
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG
import io.confluent.kafka.streams.serdes.protobuf.KafkaProtobufSerde
import okio.ByteString.Companion.toByteString
import org.apache.kafka.common.serialization.*
import java.util.*

class StoveKafkaValueSerializer<T : Any> : Serializer<T> {
  private val protobufSerde: KafkaProtobufSerde<Message> = createConfiguredSerdeForRecordValues()

  override fun serialize(
    topic: String,
    data: T
  ): ByteArray = when (data) {
    is ByteArray -> data
    else -> protobufSerde.serializer().serialize(topic, data as Message)
  }
}

class StoveKafkaValueDeserializer<T : Any> : Deserializer<Message> {
  private val protobufSerde: KafkaProtobufSerde<Message> = createConfiguredSerdeForRecordValues()

  override fun deserialize(
    topic: String,
    data: ByteArray
  ): Message = protobufSerde.deserializer().deserialize(topic, data)
}

fun createConfiguredSerdeForRecordValues(): KafkaProtobufSerde<Message> {
  val schemaRegistryClient = MockSchemaRegistry.getClientForScope("mock-registry")
  val serde: KafkaProtobufSerde<Message> = KafkaProtobufSerde<Message>(schemaRegistryClient)
  val serdeConfig: MutableMap<String, Any?> = HashMap()
  serdeConfig[SCHEMA_REGISTRY_URL_CONFIG] = "mock://mock-registry"
  serde.configure(serdeConfig, false)
  return serde
}

fun KafkaProtobufSerde<Message>.messageAsBase64(
  message: Any
): Option<Message> = Either.catch {
  deserializer()
    .deserialize(
      "any",
      Base64
        .getDecoder()
        .decode(message.toString())
        .toByteString()
        .toByteArray()
    )
}.getOrNull().toOption()

fun Message.onMatchingAssert(
  descriptor: String,
  assert: (message: Message) -> Boolean
): Boolean = descriptor == descriptorForType.name && assert(this)
