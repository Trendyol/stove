package stove.spring.standalone.example.infrastructure

import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import org.springframework.boot.jackson.autoconfigure.*
import org.springframework.context.annotation.*
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper

@Configuration
@AutoConfigureBefore(JacksonAutoConfiguration::class)
class ObjectMapperConfig {
  companion object {
    val default: JsonMapper = JsonMapper
      .builder()
      .findAndAddModules()
      .changeDefaultPropertyInclusion { inc -> inc.withValueInclusion(JsonInclude.Include.NON_NULL) }
      .configure(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, false)
      .build()
  }

  @Bean
  fun jacksonCustomizer(): JsonMapperBuilderCustomizer = JsonMapperBuilderCustomizer { builder ->
    builder
      .findAndAddModules()
      .configure(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, false)
  }
}
