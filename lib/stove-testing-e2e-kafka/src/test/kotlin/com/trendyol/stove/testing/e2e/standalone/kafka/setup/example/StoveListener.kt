package com.trendyol.stove.testing.e2e.standalone.kafka.setup.example

import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.standalone.kafka.Caching
import com.trendyol.stove.testing.e2e.standalone.kafka.Caching.getOrPut
import com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.KafkaTestShared.TopicDefinition
import io.github.nomisRev.kafka.publisher.*
import kotlinx.coroutines.*
import org.apache.kafka.clients.consumer.*
import org.apache.kafka.clients.producer.ProducerRecord
import java.time.Duration

abstract class StoveListener(
  consumerSettings: Map<String, Any>,
  publisherSettings: PublisherSettings<String, Any>
) : AutoCloseable {
  private val logger = org.slf4j.LoggerFactory.getLogger(javaClass)
  private val retryStrategy = Caching.of<String, Int>()
  abstract val topicDefinition: TopicDefinition

  private val consumer: KafkaConsumer<String, String> = KafkaConsumer<String, String>(consumerSettings)
  private val publisher: KafkaPublisher<String, Any> = KafkaPublisher(publisherSettings)

  private lateinit var consuming: Job

  @OptIn(DelicateCoroutinesApi::class)
  suspend fun start() {
    consumer.subscribe(listOf(topicDefinition.topic, topicDefinition.retryTopic, topicDefinition.deadLetterTopic))
    consuming = GlobalScope.launch {
      while (!consuming.isCancelled) {
        consumer
          .poll(Duration.ofMillis(100))
          .forEach { message ->
            logger.info("Message RECEIVED on the application side: ${message.value()}")
            consume(message, consumer)
          }
      }
    }
  }

  private suspend fun consume(message: ConsumerRecord<String, String>, consumer: KafkaConsumer<String, String>) {
    Try { listen(message) }
      .map {
        logger.info("Message COMMITTED on the application side: ${message.value()}")
        consumer.commitAsync()
      }
      .recover {
        logger.warn("CONSUMER GOT an ERROR on the application side, exception: $it")
        val currentRetry = retryStrategy.getOrPut(message.key()) { 0 }
        if (currentRetry < 3) {
          logger.warn("CONSUMER GOT an ERROR, retrying...")
          try {
            publisher.publishScope {
              offer(
                ProducerRecord(
                  // topic =
                  topicDefinition.retryTopic,
                  // key =
                  message.key(),
                  // value =
                  message.value()
                )
              )
            }
          } catch (e: Exception) {
            logger.error("Failed to publish message to retry topic: $message", e)
          }

          retryStrategy.put(message.key(), currentRetry + 1)
        } else {
          logger.error("CONSUMER GOT an ERROR, retry limit exceeded: $message")
          val record = ProducerRecord<String, Any>(
            // topic =
            topicDefinition.deadLetterTopic,
            // key =
            message.key(),
            // value =
            message.value()
          ).apply {
            headers().add("doNotFail", "true".toByteArray())
          }
          try {
            publisher.publishScope { offer(record) }
          } catch (e: Exception) {
            logger.error("Failed to publish message to dead letter topic: $message", e)
          }
        }
      }
  }

  abstract suspend fun listen(record: ConsumerRecord<String, String>)

  override fun close() {
    Try { consuming.cancel() }
  }
}
