package com.stove.spring.example.e2e

import com.trendyol.stove.testing.e2e.BaseApplicationContextInitializer
import com.trendyol.stove.testing.e2e.kafka.TestSystemKafkaInterceptor
import org.springframework.boot.SpringApplication

fun SpringApplication.addTestSystemDependencies() {
  this.addInitializers(TestSystemInitializer())
}

class TestSystemInitializer : BaseApplicationContextInitializer({
  bean<TestSystemKafkaInterceptor<String, String>>(isPrimary = true)
})
