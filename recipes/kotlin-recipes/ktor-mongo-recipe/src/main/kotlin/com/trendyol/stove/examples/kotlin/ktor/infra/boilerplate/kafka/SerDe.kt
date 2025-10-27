package com.trendyol.stove.examples.kotlin.ktor.infra.boilerplate.kafka

import com.fasterxml.jackson.module.kotlin.readValue
import com.trendyol.stove.examples.kotlin.ktor.infra.boilerplate.serialization.JacksonConfiguration
import org.apache.kafka.common.serialization.*

private val kafkaObjectMapperRef = JacksonConfiguration.default

@Suppress("UNCHECKED_CAST")
class StoveKafkaValueDeserializer<T : Any> : Deserializer<T> {
  override fun deserialize(
    topic: String,
    data: ByteArray
  ): T = kafkaObjectMapperRef.readValue<Any>(data) as T
}

class StoveKafkaValueSerializer<T : Any> : Serializer<T> {
  override fun serialize(
    topic: String,
    data: T
  ): ByteArray = kafkaObjectMapperRef.writeValueAsBytes(data)
}
