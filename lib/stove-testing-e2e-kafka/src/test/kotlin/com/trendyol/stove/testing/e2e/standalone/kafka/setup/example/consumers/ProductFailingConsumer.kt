package com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.consumers

import arrow.core.firstOrNone
import com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.KafkaTestShared.TopicDefinition
import com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.StoveListener
import io.github.nomisRev.kafka.publisher.PublisherSettings
import org.apache.kafka.clients.consumer.ConsumerRecord

@Suppress("TooGenericExceptionCaught", "TooGenericExceptionThrown")
class ProductFailingConsumer(
  consumerSettings: Map<String, Any>,
  producerSettings: PublisherSettings<String, Any>
) : StoveListener(consumerSettings, producerSettings) {
  override val topicDefinition: TopicDefinition = TopicDefinition(
    "productFailing",
    "productFailing.retry",
    "productFailing.error"
  )

  override suspend fun listen(record: ConsumerRecord<String, String>) {
    record.headers().firstOrNone { it.key() == "doNotFail" }
      .onSome { return }
      .onNone { throw Exception("exception occurred on purpose") }
  }
}
