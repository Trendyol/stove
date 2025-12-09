package com.stove.spring.example.e2e

import com.trendyol.stove.testing.e2e.kafka.TestSystemKafkaInterceptor
import com.trendyol.stove.testing.e2e.serialization.*
import com.trendyol.stove.testing.e2e.stoveSpringRegistrar
import org.springframework.boot.SpringApplication
import stove.spring.example.infrastructure.ObjectMapperConfig

fun SpringApplication.addTestSystemDependencies() {
  this.addInitializers(
    stoveSpringRegistrar {
      bean<TestSystemKafkaInterceptor<*, *>>(isPrimary = true)
      bean { StoveSerde.jackson.anyByteArraySerde(ObjectMapperConfig.default()) }
    }
  )
}
