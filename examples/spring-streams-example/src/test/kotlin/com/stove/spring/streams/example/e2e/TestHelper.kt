package com.stove.spring.streams.example.e2e

import arrow.core.*
import com.google.protobuf.Message
import com.trendyol.stove.serialization.StoveSerde
import io.confluent.kafka.streams.serdes.protobuf.KafkaProtobufSerde
import kotlinx.serialization.ExperimentalSerializationApi
import okio.ByteString.Companion.toByteString
import org.apache.kafka.common.serialization.*
import stove.spring.streams.example.kafka.KafkaRegistry
import java.util.*

class StoveKafkaValueSerializer<T : Any> : Serializer<T> {
  private val protobufSerde: KafkaProtobufSerde<Message> = KafkaRegistry.createSerde(KafkaRegistry.Mock)

  override fun serialize(
    topic: String,
    data: T
  ): ByteArray = when (data) {
    is ByteArray -> data
    else -> protobufSerde.serializer().serialize(topic, data as Message)
  }
}

class StoveKafkaValueDeserializer : Deserializer<Message> {
  private val protobufSerde: KafkaProtobufSerde<Message> = KafkaRegistry.createSerde(KafkaRegistry.Mock)

  override fun deserialize(
    topic: String,
    data: ByteArray
  ): Message = protobufSerde.deserializer().deserialize(topic, data)
}

@Suppress("UNCHECKED_CAST")
@OptIn(ExperimentalSerializationApi::class)
class StoveProtobufSerde : StoveSerde<Any, ByteArray> {
  private val parseFromMethod = "parseFrom"
  private val protobufSerde: KafkaProtobufSerde<Message> = KafkaRegistry.createSerde(KafkaRegistry.Mock)

  override fun serialize(value: Any): ByteArray = protobufSerde.serializer().serialize("any", value as Message)

  override fun <T : Any> deserialize(value: ByteArray, clazz: Class<T>): T {
    val incoming: Message = protobufSerde.deserializer().deserialize("any", value)
    incoming.isAssignableFrom(clazz).also { isAssignableFrom ->
      require(isAssignableFrom) {
        "Expected '${clazz.simpleName}' but got '${incoming.descriptorForType.name}'. " +
          "This could be transient ser/de problem since the message stream is constantly checked if the expected message is arrived, " +
          "so you can ignore this error if you are sure that the message is the expected one."
      }
    }

    val parseFromMethod = clazz.getDeclaredMethod(parseFromMethod, ByteArray::class.java)
    val parsed = parseFromMethod(incoming, incoming.toByteArray()) as T
    return parsed
  }
}

private fun Message.isAssignableFrom(clazz: Class<*>): Boolean = this.descriptorForType.name == clazz.simpleName

fun KafkaProtobufSerde<Message>.messageAsBase64(
  message: Any
): Option<Message> = Either
  .catch {
    deserializer()
      .deserialize(
        "any",
        Base64
          .getDecoder()
          .decode(message.toString())
          .toByteString()
          .toByteArray()
      )
  }.getOrNull()
  .toOption()

fun Message.onMatchingAssert(
  descriptor: String,
  assert: (message: Message) -> Boolean
): Boolean = descriptor == descriptorForType.name && assert(this)
