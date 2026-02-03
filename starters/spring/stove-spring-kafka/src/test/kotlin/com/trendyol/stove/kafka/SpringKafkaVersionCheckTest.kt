package com.trendyol.stove.kafka

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

class SpringKafkaVersionCheckTest :
  FunSpec({
    test("ensureSpringKafkaAvailable should throw when Spring Kafka is missing") {
      val error = shouldThrow<IllegalStateException> {
        SpringKafkaVersionCheck.ensureSpringKafkaAvailable()
      }

      error.message shouldContain "Spring Kafka Not Found on Classpath"
    }
  })
