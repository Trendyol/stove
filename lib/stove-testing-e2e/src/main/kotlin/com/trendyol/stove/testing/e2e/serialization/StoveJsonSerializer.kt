package com.trendyol.stove.testing.e2e.serialization

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.reflect.KClass

interface StoveJsonSerializer {
    fun serialize(value: Any): String
    fun serializeAsBytes(value: Any): ByteArray

    fun <T : Any> deserialize(
        string: String,
        clazz: KClass<T>,
    ): T

    fun <T : Any> deserialize(
        value: ByteArray,
        clazz: KClass<T>,
    ): T
}

inline fun <reified T : Any> StoveJsonSerializer.deserialize(json: String): T = this.deserialize(json, T::class)
inline fun <reified T : Any> StoveJsonSerializer.deserialize(value: ByteArray): T = this.deserialize(value, T::class)

class StoveJacksonJsonSerializer(
    private val objectMapper: ObjectMapper = jacksonObjectMapper().disable(FAIL_ON_EMPTY_BEANS),
) : StoveJsonSerializer {
    override fun serialize(value: Any): String = objectMapper.writeValueAsString(value)

    override fun serializeAsBytes(value: Any): ByteArray = objectMapper.writeValueAsBytes(value)

    override fun <T : Any> deserialize(
        string: String,
        clazz: KClass<T>,
    ): T = objectMapper.readValue(string, clazz.java)

    override fun <T : Any> deserialize(
        value: ByteArray,
        clazz: KClass<T>,
    ): T = objectMapper.readValue(value, clazz.java)
}
