package com.trendyol.stove.kafka.tests

import com.trendyol.stove.kafka.*
import com.trendyol.stove.kafka.intercepting.StoveKafkaBridge
import com.trendyol.stove.serialization.StoveSerde
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlinx.coroutines.isActive

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

    test("default bridge-port intent survives later compatibility-default changes") {
      val originalDefault = stoveKafkaBridgePortDefault
      try {
        val options = KafkaSystemOptions(configureExposedConfiguration = { emptyList() })
        stoveKafkaBridgePortDefault = "${originalDefault.toInt() + 1}"

        options.usesDefaultBridgeGrpcServerPort.shouldBeTrue()
      } finally {
        stoveKafkaBridgePortDefault = originalDefault
      }
    }

    test("KafkaContext resolves bridge server ports from the system key and captured default intent") {
      val originalDefault = stoveKafkaBridgePortDefault
      try {
        stoveKafkaBridgePortDefault = "1"
        val defaultOptions = KafkaSystemOptions(configureExposedConfiguration = { emptyList() })
        val explicitOptions = KafkaSystemOptions(
          bridgeGrpcServerPort = 31005,
          configureExposedConfiguration = { emptyList() }
        )

        KafkaContext(EmbeddedKafkaRuntime, defaultOptions).bridgeServerPort shouldBe 1
        KafkaContext(EmbeddedKafkaRuntime, defaultOptions, keyName = "keyed").bridgeServerPort shouldNotBe 1
        KafkaContext(EmbeddedKafkaRuntime, explicitOptions, keyName = "keyed").bridgeServerPort shouldBe 31005
      } finally {
        stoveKafkaBridgePortDefault = originalDefault
      }
    }

    test("non-keyed custom bridge ports are discoverable in-JVM and restored on close") {
      val previousPort = System.getProperty(STOVE_KAFKA_BRIDGE_PORT)
      System.setProperty(STOVE_KAFKA_BRIDGE_PORT, "previous-port")
      try {
        val discovery = exposeBridgePortForInJvmDiscovery(keyName = null, port = 31003)
        System.getProperty(STOVE_KAFKA_BRIDGE_PORT) shouldBe "31003"

        discovery.close()
        System.getProperty(STOVE_KAFKA_BRIDGE_PORT) shouldBe "previous-port"

        exposeBridgePortForInJvmDiscovery(keyName = "keyed", port = 31004).close()
        System.getProperty(STOVE_KAFKA_BRIDGE_PORT) shouldBe "previous-port"
      } finally {
        if (previousPort == null) {
          System.clearProperty(STOVE_KAFKA_BRIDGE_PORT)
        } else {
          System.setProperty(STOVE_KAFKA_BRIDGE_PORT, previousPort)
        }
      }
    }

    test("Kafka bridge runtimes have isolated endpoints and lifecycle") {
      val serde = StoveSerde.jackson.anyByteArraySerde()
      val first = KafkaBridgeRuntime(serde, "first")
      val second = KafkaBridgeRuntime(serde, "second")

      try {
        first.attach(31001)
        second.attach(31002)

        first.endpoint.id shouldNotBe second.endpoint.id
        first.clientProperties[KafkaBridgeConfig.BRIDGE_PORT_CONFIG] shouldBe "31001"
        first.scope.isActive.shouldBeTrue()
        second.scope.isActive.shouldBeTrue()

        first.close()
        first.scope.isActive.shouldBeFalse()
        second.scope.isActive.shouldBeTrue()
      } finally {
        first.close()
        second.close()
      }
    }
  })
