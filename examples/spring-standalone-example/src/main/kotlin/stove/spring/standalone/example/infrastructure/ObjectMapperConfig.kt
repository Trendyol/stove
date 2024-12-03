package stove.spring.standalone.example.infrastructure

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import org.springframework.boot.autoconfigure.jackson.*
import org.springframework.context.annotation.*

@Configuration
@AutoConfigureBefore(JacksonAutoConfiguration::class)
class ObjectMapperConfig {
  companion object {
    val default: ObjectMapper = ObjectMapper()
      .registerModule(KotlinModule.Builder().build())
      .findAndRegisterModules()
      .setSerializationInclusion(JsonInclude.Include.NON_NULL)
      .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
  }

  @Bean
  @Primary
  fun objectMapper(): ObjectMapper = default

  @Bean
  fun jacksonCustomizer(): Jackson2ObjectMapperBuilderCustomizer = Jackson2ObjectMapperBuilderCustomizer { builder ->
    builder.factory(default.factory)
  }
}
