package com.trendyol.stove.testing.e2e.standalone.kafka.setup.example

import com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.consumers.*
import io.github.nomisRev.kafka.publisher.PublisherSettings

object KafkaTestShared {
  data class TopicDefinition(
    val topic: String,
    val retryTopic: String,
    val deadLetterTopic: String
  )

  val topics = listOf(
    TopicDefinition("product", "product.retry", "product.error"),
    TopicDefinition("productFailing", "productFailing.retry", "productFailing.error")
  )
  val consumers: (
    consumerSettings: Map<String, Any>,
    producerSettings: PublisherSettings<String, Any>
  ) -> List<StoveListener> = { a, b ->
    listOf(
      ProductConsumer(a, b),
      ProductFailingConsumer(a, b)
    )
  }
}
