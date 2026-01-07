package com.trendyol.stove.serialization

import com.google.gson.Gson

object StoveGson {
  val default: Gson = com.google.gson
    .GsonBuilder()
    .create()

  fun byConfiguring(
    configurer: com.google.gson.GsonBuilder.() -> com.google.gson.GsonBuilder
  ): Gson = configurer(com.google.gson.GsonBuilder()).create()

  fun anyJsonStringSerde(gson: Gson = default): StoveSerde<Any, String> = StoveGsonStringSerializer(gson)

  fun anyByteArraySerde(gson: Gson = default): StoveSerde<Any, ByteArray> = StoveGsonByteArraySerializer(gson)
}

class StoveGsonStringSerializer<TIn : Any>(
  private val gson: Gson
) : StoveSerde<TIn, String> {
  override fun serialize(value: TIn): String = gson.toJson(value)

  override fun <T : TIn> deserialize(value: String, clazz: Class<T>): T = gson.fromJson(value, clazz)
}

class StoveGsonByteArraySerializer<TIn : Any>(
  private val gson: Gson
) : StoveSerde<TIn, ByteArray> {
  override fun serialize(value: TIn): ByteArray = gson.toJson(value).toByteArray()

  override fun <T : TIn> deserialize(value: ByteArray, clazz: Class<T>): T = gson.fromJson(value.toString(Charsets.UTF_8), clazz)
}
