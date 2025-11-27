package com.trendyol.stove.testing.e2e.serialization

import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.*
import tools.jackson.databind.json.JsonMapper

object StoveJackson {
  val default: JsonMapper = JsonMapper
    .builder()
    .apply {
      configure(SerializationFeature.INDENT_OUTPUT, false)
      configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
      changeDefaultPropertyInclusion { inc ->
        inc.withValueInclusion(JsonInclude.Include.NON_NULL)
      }
      findAndAddModules()
    }.build()

  fun byConfiguring(
    configurer: JsonMapper.Builder.() -> Unit
  ): JsonMapper = JsonMapper.builder(default.tokenStreamFactory()).apply(configurer).build()

  fun anyByteArraySerde(objectMapper: JsonMapper = default): StoveSerde<Any, ByteArray> = StoveJacksonByteArraySerializer(objectMapper)

  fun anyJsonStringSerde(objectMapper: JsonMapper = default): StoveSerde<Any, String> = StoveJacksonStringSerializer(objectMapper)
}

class StoveJacksonStringSerializer<TIn : Any>(
  private val objectMapper: JsonMapper
) : StoveSerde<TIn, String> {
  override fun serialize(value: TIn): String = objectMapper.writeValueAsString(value) as String

  override fun <T : TIn> deserialize(value: String, clazz: Class<T>): T = objectMapper.readValue(value, clazz)
}

class StoveJacksonByteArraySerializer<TIn : Any>(
  private val jsonMapper: JsonMapper
) : StoveSerde<TIn, ByteArray> {
  override fun serialize(value: TIn): ByteArray = jsonMapper.writeValueAsBytes(value)

  override fun <T : TIn> deserialize(value: ByteArray, clazz: Class<T>): T = jsonMapper.readValue(value, clazz)
}

/**
 * This class is used to create an object mapper with default configurations.
 * This object mapper is used to serialize and deserialize request and response bodies.
 */
object E2eObjectMapperConfig {
  /**
   * Creates an object mapper with default configurations.
   * This object mapper is used to serialize and deserialize request and response bodies.
   */
  fun createObjectMapperWithDefaults(): JsonMapper = JsonMapper
    .builder()
    .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .changeDefaultPropertyInclusion { it.withValueInclusion(JsonInclude.Include.NON_NULL) }
    .findAndAddModules()
    .build()
}
