package com.trendyol.stove.kafka

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class KafkaOptionsTest :
  FunSpec({
    test("provided options should expose config and runMigrations flag") {
      val options = KafkaSystemOptions.provided(
        bootstrapServers = "localhost:9092",
        runMigrations = false,
        configureExposedConfiguration = { listOf("bootstrap=${it.bootstrapServers}") }
      )

      options.providedConfig.bootstrapServers shouldBe "localhost:9092"
      options.runMigrationsForProvided shouldBe false
      options.configureExposedConfiguration(options.providedConfig) shouldBe listOf("bootstrap=localhost:9092")
    }

    test("default kafka ports should include 9092 and 9093") {
      KafkaSystemOptions.DEFAULT_KAFKA_PORTS shouldBe listOf(9092, 9093)
    }
  })
