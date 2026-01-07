package com.trendyol.stove.kafka

import org.apache.kafka.common.serialization.*

@Suppress("UNCHECKED_CAST")
class StoveKafkaValueDeserializer<T : Any> : Deserializer<T> {
  override fun deserialize(
    topic: String,
    data: ByteArray
  ): T = stoveSerdeRef.deserialize(data, Any::class.java) as T
}

class StoveKafkaValueSerializer<T : Any> : Serializer<T> {
  override fun serialize(
    topic: String,
    data: T
  ): ByteArray = stoveSerdeRef.serialize(data)
}
