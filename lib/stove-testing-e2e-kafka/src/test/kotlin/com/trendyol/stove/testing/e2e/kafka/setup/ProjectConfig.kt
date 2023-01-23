package com.trendyol.stove.testing.e2e.kafka.setup

import com.trendyol.stove.testing.e2e.kafka.KafkaSystemOptions
import com.trendyol.stove.testing.e2e.kafka.StoveKafkaValueDeserializer
import com.trendyol.stove.testing.e2e.kafka.StoveKafkaValueSerializer
import com.trendyol.stove.testing.e2e.kafka.setup.example.KafkaTestShared
import com.trendyol.stove.testing.e2e.kafka.withKafka
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import io.kotest.common.ExperimentalKotest
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.listeners.AfterEachListener
import io.kotest.core.listeners.BeforeEachListener
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer

class KafkaApplicationUnderTest : ApplicationUnderTest<Unit> {
    private lateinit var client: AdminClient
    private val consumers: MutableList<AutoCloseable> = mutableListOf()

    override suspend fun start(configurations: List<String>) {
        val bootstrapServers = configurations.first { it.contains("kafka", true) }.split('=')[1]
        client = mapOf<String, Any>(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers
        ).let { AdminClient.create(it) }

        val newTopics = KafkaTestShared.topics.flatMap { listOf(it.topic, it.retryTopic, it.deadLetterTopic) }.map { NewTopic(it, 1, 1) }
        client.createTopics(newTopics).all().get()
        startConsumers(bootstrapServers)
    }

    private suspend fun startConsumers(bootStrapServers: String) {
        val consumerSettings = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootStrapServers,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to true,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StoveKafkaValueDeserializer::class.java,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.GROUP_ID_CONFIG to "stove-application-consumers"
        )

        val producerSettings = mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootStrapServers,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StoveKafkaValueSerializer::class.java,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.ACKS_CONFIG to "1"
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
class ProjectConfig : AbstractProjectConfig(), BeforeEachListener, AfterEachListener {

    override suspend fun beforeProject() {
        TestSystem()
            .withKafka(
                KafkaSystemOptions(configureExposedConfiguration = { cfg ->
                    listOf("kafka.servers=${cfg.boostrapServers}")
                })
            )
            .applicationUnderTest(KafkaApplicationUnderTest())
            .run()
    }

    override suspend fun afterProject() {
        TestSystem.instance.close()
    }

    override fun extensions(): List<Extension> {
        return listOf(this)
    }
}
