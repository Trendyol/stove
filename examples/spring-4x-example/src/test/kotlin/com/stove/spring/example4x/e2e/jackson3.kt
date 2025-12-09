package com.stove.spring.example4x.e2e

import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper

class StoveJackson3ThroughIfStringSerde(
  private val jsonMapper: JsonMapper
) : StoveSerde<Any, ByteArray> {
  private val logger = LoggerFactory.getLogger(javaClass)

  override fun serialize(value: Any): ByteArray = when (value) {
    is ByteArray -> {
      logger.info("Value is already a ByteArray, returning as is.")
      value
    }

    is String -> {
      logger.info("Serializing String value.")
      val byteArray = value.toByteArray()
      byteArray
    }

    else -> {
      logger.info("Serializing value of type: {}", value::class.java.name)
      val byteArray = runCatching { jsonMapper.writeValueAsBytes(value) }
        .onFailure { logger.error("Serialization failed", it) }
        .getOrThrow()
      byteArray
    }
  }

  override fun <T : Any> deserialize(value: ByteArray, clazz: Class<T>): T {
    logger.info("Deserializing to class: {}", clazz.name)
    val value = runCatching {
      jsonMapper.readValue(value, clazz)
    }.onFailure { logger.error("Deserialization failed", it) }.getOrThrow()
    logger.info("Deserialized value: {}", value)
    return value
  }
}
