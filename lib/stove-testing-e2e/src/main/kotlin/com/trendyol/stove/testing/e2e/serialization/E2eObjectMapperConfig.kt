package com.trendyol.stove.testing.e2e.serialization

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.time.Instant

/**
 * This class is used to create an object mapper with default configurations.
 * This object mapper is used to serialize and deserialize request and response bodies.
 */
class E2eObjectMapperConfig {

    companion object {
        /**
         * Creates an object mapper with default configurations.
         * This object mapper is used to serialize and deserialize request and response bodies.
         */
        fun createObjectMapperWithDefaults(): ObjectMapper {
            val isoInstantModule =
                SimpleModule()
                    .addSerializer(Instant::class.java, IsoInstantSerializer())
                    .addDeserializer(Instant::class.java, IsoInstantDeserializer())

            return JsonMapper.builder()
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .build()
                .registerKotlinModule()
                .registerModule(isoInstantModule)
        }
    }
}
