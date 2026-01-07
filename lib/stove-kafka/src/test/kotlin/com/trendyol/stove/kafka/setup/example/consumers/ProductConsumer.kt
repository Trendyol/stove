package com.trendyol.stove.kafka.setup.example.consumers

import com.trendyol.stove.kafka.setup.example.KafkaTestShared.TopicDefinition
import com.trendyol.stove.kafka.setup.example.StoveListener
import io.github.nomisRev.kafka.publisher.PublisherSettings
import org.apache.kafka.clients.consumer.ConsumerRecord

// TODO: Convert into in-flight consumer
class ProductConsumer(
  consumerSettings: Map<String, Any>,
  producerSettings: PublisherSettings<String, Any>
) : StoveListener(consumerSettings, producerSettings) {
  private val logger = org.slf4j.LoggerFactory.getLogger(javaClass)
  override val topicDefinition: TopicDefinition = TopicDefinition("product", "product.retry", "product.error")

  override suspend fun listen(record: ConsumerRecord<String, String>) {
    logger.info("Product consumed: ${record.value()} from topic: ${record.topic()} with key: ${record.key()}")
  }
}
