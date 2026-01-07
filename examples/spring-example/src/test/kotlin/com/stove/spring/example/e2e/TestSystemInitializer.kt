package com.stove.spring.example.e2e

import com.trendyol.stove.kafka.TestSystemKafkaInterceptor
import com.trendyol.stove.serialization.*
import com.trendyol.stove.spring.stoveSpringRegistrar
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
