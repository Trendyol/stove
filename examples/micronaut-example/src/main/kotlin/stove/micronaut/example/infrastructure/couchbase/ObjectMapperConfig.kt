package stove.micronaut.example.infrastructure.couchbase

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory

@Factory
class ObjectMapperConfig {

  companion object {
    fun createObjectMapperWithDefaults(): ObjectMapper {
      val isoInstantModule = SimpleModule()
      return ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .registerModule(isoInstantModule)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
  }

  @Bean
  fun objectMapper(): ObjectMapper {
    return createObjectMapperWithDefaults()
  }
}
