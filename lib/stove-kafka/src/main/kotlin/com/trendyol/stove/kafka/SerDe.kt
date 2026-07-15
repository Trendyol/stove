package com.trendyol.stove.kafka

import com.trendyol.stove.serialization.StoveSerde
import org.apache.kafka.common.serialization.*

@Suppress("UNCHECKED_CAST")
class StoveKafkaValueDeserializer<T : Any> : Deserializer<T> {
  override fun deserialize(
    topic: String,
    data: ByteArray
  ): T = stoveSerdeRef.deserialize(data, Any::class.java) as T
}

class StoveKafkaValueSerializer<T : Any>(
  private val serde: StoveSerde<Any, ByteArray> = stoveSerdeRef
) : Serializer<T> {
  override fun serialize(
    topic: String,
    data: T
  ): ByteArray = serde.serialize(data)
}
