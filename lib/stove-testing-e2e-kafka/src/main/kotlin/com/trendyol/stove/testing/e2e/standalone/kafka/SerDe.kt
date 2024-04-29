package com.trendyol.stove.testing.e2e.standalone.kafka

import com.fasterxml.jackson.module.kotlin.readValue
import org.apache.kafka.common.serialization.*

@Suppress("UNCHECKED_CAST")
class StoveKafkaValueDeserializer<T : Any> : Deserializer<T> {
  override fun deserialize(
    topic: String,
    data: ByteArray
  ): T = stoveKafkaObjectMapperRef.readValue<Any>(data) as T
}

class StoveKafkaValueSerializer<T : Any> : Serializer<T> {
  override fun serialize(
    topic: String,
    data: T
  ): ByteArray = stoveKafkaObjectMapperRef.writeValueAsBytes(data)
}
