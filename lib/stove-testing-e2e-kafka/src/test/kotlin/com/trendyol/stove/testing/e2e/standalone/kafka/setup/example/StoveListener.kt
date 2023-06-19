package com.trendyol.stove.testing.e2e.standalone.kafka.setup.example

import com.trendyol.stove.functional.Try
import com.trendyol.stove.functional.recover
import com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.KafkaTestShared.TopicDefinition
import io.github.nomisRev.kafka.sendAwait
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asFlow
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import java.time.Duration

abstract class StoveListener(
    consumerSettings: Map<String, Any>,
    producerSettings: Map<String, Any>
) : AutoCloseable {

    protected val logger = org.slf4j.LoggerFactory.getLogger(javaClass)

    abstract val topicDefinition: TopicDefinition

    private val consumer: KafkaConsumer<String, String> = KafkaConsumer<String, String>(consumerSettings)
    private val producer: KafkaProducer<String, String> = KafkaProducer(producerSettings)

    private lateinit var consuming: Job

    @OptIn(DelicateCoroutinesApi::class)
    suspend fun start() {
        consumer.subscribe(listOf(topicDefinition.topic, topicDefinition.retryTopic, topicDefinition.retryTopic))
        val retryStrategy = mutableMapOf<String, Int>()
        consuming = GlobalScope.launch {
            while (!consuming.isCancelled) {
                consumer
                    .poll(Duration.ofMillis(100))
                    .asSequence()
                    .asFlow()
                    .collect { message ->
                        logger.info("Message RECEIVED on the application side: ${message.value()}")
                        Try { listen(message) }
                            .map {
                                consumer.commitSync()
                                logger.info("Message COMMITTED on the application side: ${message.value()}")
                            }
                            .recover {
                                logger.warn("CONSUMER GOT an ERROR on the application side, exception: $it")
                                retryStrategy[message.value()] = retryStrategy.getOrPut(message.value()) { 0 } + 1
                                if (retryStrategy[message.value()]!! < 3) {
                                    logger.warn("CONSUMER GOT an ERROR, retrying...")
                                    producer.sendAwait(ProducerRecord(topicDefinition.retryTopic, message.value()))
                                }
                            }
                    }
            }
        }
    }

    abstract suspend fun listen(record: ConsumerRecord<String, String>)

    override fun close() {
        Try { consuming.cancel() }
    }
}
