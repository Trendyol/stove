package com.stove.spring.example.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.BaseApplicationContextInitializer
import com.trendyol.stove.testing.e2e.kafka.TestSystemKafkaInterceptor
import org.springframework.boot.SpringApplication

fun SpringApplication.addTestSystemDependencies() {
    this.addInitializers(TestSystemInitializer())
}

class TestSystemInitializer :
    BaseApplicationContextInitializer({
        bean<TestSystemKafkaInterceptor>(isPrimary = true)
        bean<ObjectMapper> { ref("objectMapper") }
    })
