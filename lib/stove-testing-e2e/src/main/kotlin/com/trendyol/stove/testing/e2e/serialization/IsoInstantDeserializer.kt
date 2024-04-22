package com.trendyol.stove.testing.e2e.serialization

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.trendyol.stove.functional.Try
import com.trendyol.stove.functional.recover
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor

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
  @Throws(IOException::class, JsonProcessingException::class)
  override fun serialize(
    value: Instant,
    gen: JsonGenerator,
    serializers: SerializerProvider?
  ) {
    gen.writeString(value.toString())
  }
}
