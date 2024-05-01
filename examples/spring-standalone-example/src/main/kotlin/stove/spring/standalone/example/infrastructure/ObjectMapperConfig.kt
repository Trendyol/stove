package stove.spring.standalone.example.infrastructure

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.context.annotation.*

@Configuration
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
  fun objectMapper(): ObjectMapper = createObjectMapperWithDefaults()
}
