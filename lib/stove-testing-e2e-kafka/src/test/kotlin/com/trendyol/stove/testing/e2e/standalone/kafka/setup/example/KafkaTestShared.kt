package com.trendyol.stove.testing.e2e.standalone.kafka.setup.example

import com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.consumers.BacklogConsumer
import com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.consumers.ProductConsumer
import com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.consumers.ProductFailingConsumer

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
    val consumers: (consumerSettings: Map<String, Any>, producerSettings: Map<String, Any>) -> List<StoveListener> =
        { a, b ->
            listOf(
                ProductConsumer(a, b),
                BacklogConsumer(a, b),
                ProductFailingConsumer(a, b)
            )
        }
}
