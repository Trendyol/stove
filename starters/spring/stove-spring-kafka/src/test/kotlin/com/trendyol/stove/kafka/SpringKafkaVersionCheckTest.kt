package com.trendyol.stove.kafka

import io.kotest.core.spec.style.FunSpec

class SpringKafkaVersionCheckTest :
  FunSpec({
    test("ensureSpringKafkaAvailable should not throw when Spring Kafka is on classpath") {
      SpringKafkaVersionCheck.ensureSpringKafkaAvailable()
    }
  })
