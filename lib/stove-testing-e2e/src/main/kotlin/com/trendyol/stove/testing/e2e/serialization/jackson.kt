package com.trendyol.stove.testing.e2e.serialization

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.*
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.*
import com.trendyol.stove.functional.*
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor

object StoveJackson {
  val default: ObjectMapper = jacksonObjectMapper().disable(FAIL_ON_EMPTY_BEANS).apply {
    findAndRegisterModules()
  }

  fun byConfiguring(
    configurer: JsonMapper.Builder.() -> Unit
  ): ObjectMapper = JsonMapper.builder(default.factory).apply(configurer).build()

  fun anyByteArraySerde(objectMapper: ObjectMapper = default): StoveSerde<Any, ByteArray> = StoveJacksonByteArraySerializer(objectMapper)

  fun anyJsonStringSerde(objectMapper: ObjectMapper = default): StoveSerde<Any, String> = StoveJacksonStringSerializer(objectMapper)
}

class StoveJacksonStringSerializer<TIn : Any>(
  private val objectMapper: ObjectMapper
) : StoveSerde<TIn, String> {
  override fun serialize(value: TIn): String = objectMapper.writeValueAsString(value) as String

  override fun <T : TIn> deserialize(value: String, clazz: Class<T>): T = objectMapper.readValue(value, clazz)
}

class StoveJacksonByteArraySerializer<TIn : Any>(
  private val objectMapper: ObjectMapper
) : StoveSerde<TIn, ByteArray> {
  override fun serialize(value: TIn): ByteArray = objectMapper.writeValueAsBytes(value)

  override fun <T : TIn> deserialize(value: ByteArray, clazz: Class<T>): T = objectMapper.readValue(value, clazz)
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
  fun createObjectMapperWithDefaults(): ObjectMapper {
    val isoInstantModule = SimpleModule()
      .addSerializer(Instant::class.java, IsoInstantSerializer())
      .addDeserializer(Instant::class.java, IsoInstantDeserializer())

    return JsonMapper
      .builder()
      .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
      .serializationInclusion(JsonInclude.Include.NON_NULL)
      .build()
      .registerKotlinModule()
      .registerModule(isoInstantModule)
  }
}

/**
 * Instant serializer deserializer for jackson
 */
class IsoInstantDeserializer : JsonDeserializer<Instant>() {
  override fun deserialize(
    parser: JsonParser,
    context: DeserializationContext
  ): Instant {
    val string: String = parser.text.trim()
    return Try {
      DateTimeFormatter.ISO_INSTANT.parse(string) { temporal: TemporalAccessor ->
        Instant.from(temporal)
      } as Instant
    }.recover { Instant.ofEpochSecond(string.toLong()) }.get()
  }
}

/**
 * Instant serializer for jackson
 */
class IsoInstantSerializer : JsonSerializer<Instant>() {
  override fun serialize(
    value: Instant,
    gen: JsonGenerator,
    serializers: SerializerProvider?
  ) {
    gen.writeString(value.toString())
  }
}
