package stove.spring.example.infrastructure

import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import org.springframework.boot.jackson.autoconfigure.*
import org.springframework.context.annotation.*
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper

@Configuration
@AutoConfigureBefore(JacksonAutoConfiguration::class)
class ObjectMapperConfig {
  companion object {
    fun default(): JsonMapper = JsonMapper
      .builder()
      .apply {
        findAndAddModules()
        // addModule(JsonValueModule())
        changeDefaultPropertyInclusion { it.withValueInclusion(JsonInclude.Include.NON_NULL) }
        configure(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, false)
      }.build()
  }

  @Bean
  fun jacksonCustomizer(): JsonMapperBuilderCustomizer = JsonMapperBuilderCustomizer { builder ->
    builder
      .findAndAddModules()
      .configure(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, false)
  }
}
