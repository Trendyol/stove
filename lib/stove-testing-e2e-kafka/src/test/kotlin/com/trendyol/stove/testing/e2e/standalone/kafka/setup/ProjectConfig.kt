package com.trendyol.stove.testing.e2e.standalone.kafka.setup

import com.trendyol.stove.testing.e2e.standalone.kafka.*
import com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.KafkaTestShared
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import io.github.nomisRev.kafka.publisher.PublisherSettings
import io.kotest.common.ExperimentalKotest
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.extensions.system.SystemEnvironmentProjectListener
import org.apache.kafka.clients.admin.*
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.*
import java.util.*

class KafkaApplicationUnderTest : ApplicationUnderTest<Unit> {
  private lateinit var client: AdminClient
  private val consumers: MutableList<AutoCloseable> = mutableListOf()

  override suspend fun start(configurations: List<String>) {
    val bootstrapServers = configurations.first { it.contains("kafka", true) }.split('=')[1]
    client = mapOf<String, Any>(
      AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers
    ).let { AdminClient.create(it) }

    val newTopics = KafkaTestShared.topics.flatMap {
      listOf(it.topic, it.retryTopic, it.deadLetterTopic)
    }.map { NewTopic(it, 1, 1) }
    client.createTopics(newTopics).all().get()
    startConsumers(bootstrapServers)
  }

  private suspend fun startConsumers(bootStrapServers: String) {
    val consumerSettings = mapOf(
      ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootStrapServers,
      ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to true,
      ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StoveKafkaValueDeserializer::class.java,
      ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
      ConsumerConfig.GROUP_ID_CONFIG to "stove-application-consumers",
      ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG to listOf("com.trendyol.stove.testing.e2e.standalone.kafka.intercepting.StoveKafkaBridge")
    )

    val producerSettings = PublisherSettings<String, String>(
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

@ExperimentalKotest
class ProjectConfig : AbstractProjectConfig() {
  override fun extensions(): List<Extension> = listOf(
    SystemEnvironmentProjectListener(STOVE_KAFKA_BRIDGE_PORT, STOVE_KAFKA_BRIDGE_PORT_DEFAULT)
  )

  override suspend fun beforeProject(): Unit = TestSystem()
    .with {
      kafka {
        KafkaSystemOptions(
          configureExposedConfiguration = { cfg ->
            listOf("kafka.servers=${cfg.bootstrapServers}")
          }
        )
      }
      applicationUnderTest(KafkaApplicationUnderTest())
    }.run()

  override suspend fun afterProject(): Unit = TestSystem.stop()
}
