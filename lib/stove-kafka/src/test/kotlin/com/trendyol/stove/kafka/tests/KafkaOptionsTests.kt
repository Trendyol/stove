package com.trendyol.stove.kafka.tests

import com.trendyol.stove.kafka.*
import com.trendyol.stove.kafka.intercepting.StoveKafkaBridge
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank

class KafkaOptionsTests :
  FunSpec({

    test("KafkaSystemOptions should have sensible defaults") {
      val options = object : KafkaSystemOptions(
        configureExposedConfiguration = { _ -> listOf() }
      ) {}

      options.useEmbeddedKafka shouldBe false
      options.topicSuffixes shouldBe TopicSuffixes()
      options.listenPublishedMessagesFromStove shouldBe false
      options.serde shouldNotBe null
      options.valueSerializer shouldNotBe null
      options.containerOptions shouldNotBe null
    }

    test("KafkaSystemOptions.provided should create ProvidedKafkaSystemOptions with correct config") {
      val options = KafkaSystemOptions.provided(
        bootstrapServers = "localhost:9092",
        configureExposedConfiguration = { cfg ->
          listOf("kafka.bootstrap-servers=${cfg.bootstrapServers}")
        }
      )

      options.providedConfig.bootstrapServers shouldBe "localhost:9092"
      options.providedConfig.interceptorClass shouldBe StoveKafkaBridge::class.java.name
      options.runMigrationsForProvided shouldBe true
      options.useEmbeddedKafka.shouldBeFalse()
    }

    test("ProvidedKafkaSystemOptions should expose correct properties") {
      val config = KafkaExposedConfiguration(
        bootstrapServers = "broker1:9092,broker2:9092",
        interceptorClass = "com.example.Interceptor"
      )
      val options = ProvidedKafkaSystemOptions(
        config = config,
        runMigrations = false,
        configureExposedConfiguration = { cfg ->
          listOf("servers=${cfg.bootstrapServers}")
        }
      )

      options.config shouldBe config
      options.providedConfig shouldBe config
      options.runMigrationsForProvided shouldBe false
    }

    test("KafkaExposedConfiguration should hold bootstrap servers and interceptor class") {
      val cfg = KafkaExposedConfiguration(
        bootstrapServers = "host1:9092",
        interceptorClass = "com.test.MyInterceptor"
      )

      cfg.bootstrapServers shouldBe "host1:9092"
      cfg.interceptorClass shouldBe "com.test.MyInterceptor"
    }

    test("KafkaContainerOptions should have defaults") {
      val opts = KafkaContainerOptions()
      opts shouldNotBe null
    }

    test("stoveKafkaBridgePortDefault should return a valid port string") {
      stoveKafkaBridgePortDefault.shouldNotBeBlank()
      stoveKafkaBridgePortDefault.toInt() shouldNotBe 0
    }
  })
