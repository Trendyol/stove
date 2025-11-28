package com.stove.spring.example.e2e

import com.trendyol.stove.testing.e2e.kafka.TestSystemKafkaInterceptor
import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import org.springframework.beans.factory.BeanRegistrarDsl
import org.springframework.boot.SpringApplication
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.support.GenericApplicationContext
import stove.spring.example.infrastructure.ObjectMapperConfig

fun SpringApplication.addTestSystemDependencies() {
  this.addInitializers(
    ApplicationContextInitializer<GenericApplicationContext> { context ->
      BeanRegistrarDsl {
        registerBean<TestSystemKafkaInterceptor<*, *>>(primary = true)
        registerBean { StoveSerde.jackson.anyByteArraySerde(ObjectMapperConfig.default()) }
      }.also { context.register(it) }
    }
  )
}
