package com.trendyol.stove.testing.e2e.standalone.kafka.setup

import com.trendyol.stove.testing.e2e.database.migrations.DatabaseMigration
import com.trendyol.stove.testing.e2e.reporting.StoveKotestExtension
import com.trendyol.stove.testing.e2e.standalone.kafka.*
import com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.KafkaTestShared
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import io.github.nomisRev.kafka.publisher.PublisherSettings
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import org.apache.kafka.clients.admin.*
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.*
import org.slf4j.*
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName
import java.util.*

// ============================================================================
// Shared components
// ============================================================================

class KafkaApplicationUnderTest : ApplicationUnderTest<Unit> {
  private val logger: Logger = LoggerFactory.getLogger(javaClass)
  private lateinit var client: AdminClient
  private val consumers: MutableList<AutoCloseable> = mutableListOf()

  override suspend fun start(configurations: List<String>) {
    val bootstrapServers = configurations.first { it.contains("kafka", true) }.split('=')[1]
    logger.info("Starting Kafka application with bootstrap servers: $bootstrapServers")

    client = mapOf<String, Any>(
      AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers
    ).let { AdminClient.create(it) }

    val newTopics = KafkaTestShared.topics
      .flatMap { listOf(it.topic, it.retryTopic, it.deadLetterTopic) }
      .map { NewTopic(it, 1, 1) }
    client.createTopics(newTopics).all().get()
    startConsumers(bootstrapServers)
  }

  private suspend fun startConsumers(bootStrapServers: String) {
    val consumerSettings = mapOf(
      ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootStrapServers,
      ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG to "2000",
      ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG to "true",
      ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StoveKafkaValueDeserializer::class.java,
      ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
      ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
      ConsumerConfig.GROUP_ID_CONFIG to "stove-application-consumers",
      ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG to listOf("com.trendyol.stove.testing.e2e.standalone.kafka.intercepting.StoveKafkaBridge")
    )

    val producerSettings = PublisherSettings<String, Any>(
      bootStrapServers,
      StringSerializer(),
      StoveKafkaValueSerializer(),
      properties = Properties().apply {
        put(
          ProducerConfig.INTERCEPTOR_CLASSES_CONFIG,
          listOf("com.trendyol.stove.testing.e2e.standalone.kafka.intercepting.StoveKafkaBridge")
        )
      }
    )

    val listeners = KafkaTestShared.consumers(consumerSettings, producerSettings)
    listeners.forEach { it.start() }
    consumers.addAll(listeners)
  }

  override suspend fun stop() {
    client.close()
    consumers.forEach { it.close() }
  }
}

/**
 * Migration that creates additional topics for testing.
 */
class CreateTestTopicsMigration : DatabaseMigration<KafkaMigrationContext> {
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  override val order: Int = 1

  override suspend fun execute(connection: KafkaMigrationContext) {
    logger.info("Executing CreateTestTopicsMigration")
    val topics = listOf(
      NewTopic("migration-test-topic", 1, 1),
      NewTopic("migration-test-topic-2", 2, 1)
    )
    connection.admin
      .createTopics(topics)
      .all()
      .get()
    logger.info("Created migration test topics")
  }
}

// ============================================================================
// Strategy interface
// ============================================================================

sealed interface KafkaTestStrategy {
  val logger: Logger

  suspend fun start()

  suspend fun stop()

  companion object {
    fun select(): KafkaTestStrategy {
      val useProvided = System.getenv("USE_PROVIDED")?.toBoolean()
        ?: System.getProperty("useProvided")?.toBoolean()
        ?: false

      val useEmbedded = System.getenv("USE_EMBEDDED")?.toBoolean()
        ?: System.getProperty("useEmbeddedKafka")?.toBoolean()
        ?: false

      return when {
        useProvided -> ProvidedKafkaStrategy()
        useEmbedded -> EmbeddedKafkaStrategy()
        else -> ContainerKafkaStrategy()
      }
    }
  }
}

// ============================================================================
// Container-based strategy (default)
// ============================================================================

class ContainerKafkaStrategy : KafkaTestStrategy {
  override val logger: Logger = LoggerFactory.getLogger(javaClass)

  init {
    setupBridgePort()
  }

  override suspend fun start() {
    logger.info("Starting Kafka tests with container mode")

    val options = KafkaSystemOptions(
      useEmbeddedKafka = false,
      listenPublishedMessagesFromStove = true,
      containerOptions = KafkaContainerOptions(tag = "7.8.1"),
      configureExposedConfiguration = { cfg ->
        listOf("kafka.servers=${cfg.bootstrapServers}")
      }
    ).migrations {
      register<CreateTestTopicsMigration>()
    }

    TestSystem()
      .with {
        kafka { options }
        applicationUnderTest(KafkaApplicationUnderTest())
      }.run()
  }

  override suspend fun stop() {
    TestSystem.stop()
    logger.info("Kafka container tests completed")
  }
}

// ============================================================================
// Embedded Kafka strategy
// ============================================================================

class EmbeddedKafkaStrategy : KafkaTestStrategy {
  override val logger: Logger = LoggerFactory.getLogger(javaClass)

  init {
    setupBridgePort()
  }

  override suspend fun start() {
    logger.info("Starting Kafka tests with embedded mode")

    val options = KafkaSystemOptions(
      useEmbeddedKafka = true,
      listenPublishedMessagesFromStove = true,
      configureExposedConfiguration = { cfg ->
        listOf("kafka.servers=${cfg.bootstrapServers}")
      }
    ).migrations {
      register<CreateTestTopicsMigration>()
    }

    TestSystem()
      .with {
        kafka { options }
        applicationUnderTest(KafkaApplicationUnderTest())
      }.run()
  }

  override suspend fun stop() {
    TestSystem.stop()
    logger.info("Kafka embedded tests completed")
  }
}

// ============================================================================
// Provided instance strategy
// ============================================================================

class ProvidedKafkaStrategy : KafkaTestStrategy {
  override val logger: Logger = LoggerFactory.getLogger(javaClass)

  private lateinit var externalContainer: ConfluentKafkaContainer

  init {
    setupBridgePort()
  }

  override suspend fun start() {
    logger.info("Starting Kafka tests with provided mode")

    // Start an external container to simulate a provided instance
    externalContainer = ConfluentKafkaContainer(
      DockerImageName.parse("confluentinc/cp-kafka:7.8.1")
    ).apply { start() }

    logger.info("External Kafka container started at ${externalContainer.bootstrapServers}")

    val options = KafkaSystemOptions
      .provided(
        bootstrapServers = externalContainer.bootstrapServers,
        listenPublishedMessagesFromStove = true,
        runMigrations = true,
        cleanup = { admin ->
          logger.info("Running cleanup on provided instance")
          val topics = admin
            .listTopics()
            .names()
            .get()
            .filter { it.startsWith("migration-test") }
          if (topics.isNotEmpty()) {
            admin.deleteTopics(topics).all().get()
          }
        },
        configureExposedConfiguration = { cfg ->
          listOf("kafka.servers=${cfg.bootstrapServers}")
        }
      ).migrations {
        register<CreateTestTopicsMigration>()
      }

    TestSystem()
      .with {
        kafka { options }
        applicationUnderTest(KafkaApplicationUnderTest())
      }.run()
  }

  override suspend fun stop() {
    TestSystem.stop()
    externalContainer.stop()
    logger.info("Kafka provided tests completed")
  }
}

// ============================================================================
// Helper function
// ============================================================================

private fun setupBridgePort() {
  stoveKafkaBridgePortDefault = PortFinder.findAvailablePortAsString()
  System.setProperty(STOVE_KAFKA_BRIDGE_PORT, stoveKafkaBridgePortDefault)
}

// ============================================================================
// Kotest project config - selects strategy based on environment
// ============================================================================

class Stove : AbstractProjectConfig() {
  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  private val strategy = KafkaTestStrategy.select()

  override suspend fun beforeProject() = strategy.start()

  override suspend fun afterProject() = strategy.stop()
}
