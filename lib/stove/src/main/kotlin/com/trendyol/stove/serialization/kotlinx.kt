package com.trendyol.stove.serialization

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream

object StoveKotlinx {
  val default: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    explicitNulls = false
  }

  fun byConfiguring(configurer: JsonBuilder.() -> Unit): Json = Json(default) { configurer() }

  fun anyJsonStringSerde(json: Json = default): StoveSerde<Any, String> = StoveKotlinxStringSerializer(json)

  fun anyByteArraySerde(json: Json = default): StoveSerde<Any, ByteArray> = StoveKotlinxByteArraySerializer(json)
}

@Suppress("UNCHECKED_CAST")
class StoveKotlinxStringSerializer<TIn : Any>(
  private val json: Json
) : StoveSerde<TIn, String> {
  override fun serialize(value: TIn): String {
    value as Any
    return json.encodeToString(serializer(value::class.java), value)
  }

  override fun <T : TIn> deserialize(value: String, clazz: Class<T>): T = json.decodeFromString(serializer(clazz), value) as T
}

class StoveKotlinxByteArraySerializer(
  private val json: Json
) : StoveSerde<Any, ByteArray> {
  @OptIn(ExperimentalSerializationApi::class)
  override fun serialize(value: Any): ByteArray = ByteArrayOutputStream().use { stream ->
    json.encodeToStream(serializer(value::class.java), value, stream)
    stream.toByteArray()
  }

  @OptIn(ExperimentalSerializationApi::class)
  @Suppress("UNCHECKED_CAST")
  override fun <T : Any> deserialize(value: ByteArray, clazz: Class<T>): T =
    json.decodeFromStream(serializer(clazz), value.inputStream()) as T
}
