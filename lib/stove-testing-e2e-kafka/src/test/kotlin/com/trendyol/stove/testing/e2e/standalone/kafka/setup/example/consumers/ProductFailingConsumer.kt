package com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.consumers

import com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.KafkaTestShared.TopicDefinition
import com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.StoveListener
import io.github.nomisRev.kafka.publisher.PublisherSettings
import org.apache.kafka.clients.consumer.ConsumerRecord

@Suppress("TooGenericExceptionCaught", "TooGenericExceptionThrown")
class ProductFailingConsumer(
  consumerSettings: Map<String, Any>,
  producerSettings: PublisherSettings<String, String>
) : StoveListener(consumerSettings, producerSettings) {
  override val topicDefinition: TopicDefinition = TopicDefinition(
    "productFailing",
    "productFailing.retry",
    "productFailing.error"
  )

  override suspend fun listen(record: ConsumerRecord<String, String>) {
    throw Exception("exception occurred on purpose")
  }
}
