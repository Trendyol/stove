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
    TopicDefinition("productFailing", "productFailing.retry", "productFailing.error"),
    TopicDefinition("backlog", "backlog.retry", "backlog.error")
  )
  val consumers:
    (consumerSettings: Map<String, Any>, producerSettings: PublisherSettings<String, String>) -> List<StoveListener> =
    { a, b ->
      listOf(
        ProductConsumer(a, b),
        BacklogConsumer(a, b),
        ProductFailingConsumer(a, b)
      )
    }
}
