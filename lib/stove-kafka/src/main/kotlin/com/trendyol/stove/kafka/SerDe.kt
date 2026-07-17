package com.trendyol.stove.kafka

import com.trendyol.stove.serialization.StoveSerde
import org.apache.kafka.common.serialization.*

@Suppress("UNCHECKED_CAST")
class StoveKafkaValueDeserializer<T : Any> : Deserializer<T> {
  override fun deserialize(
    topic: String,
    data: ByteArray?
  ): T? = data?.let { stoveSerdeRef.deserialize(it, Any::class.java) as T }
}

class StoveKafkaValueSerializer<T : Any>(
  private val serde: StoveSerde<Any, ByteArray> = stoveSerdeRef
) : Serializer<T> {
  override fun serialize(
    topic: String,
    data: T?
  ): ByteArray? = when (data) {
    // Tombstones serialize to null so Kafka stores a null value, not empty bytes.
    null -> null

    // Raw payloads go to the wire byte-for-byte, matching the bridge's observation path.
    is ByteArray -> data

    else -> serde.serialize(data)
  }
}
