package com.trendyol.stove.kafka.setup.example

import com.trendyol.stove.kafka.setup.example.consumers.*
import io.github.nomisRev.kafka.publisher.PublisherSettings
import org.apache.kafka.clients.consumer.ConsumerConfig
import java.util.UUID

object KafkaTestShared {
  data class TopicDefinition(
    val topic: String,
    val retryTopic: String = "$topic.retry",
    val deadLetterTopic: String = "$topic.error"
  )

  // Each application scenario owns its partitions, offsets, and consumer group.
  val product = TopicDefinition("product-${UUID.randomUUID()}")
  val bulk = TopicDefinition("bulk-${UUID.randomUUID()}")
  val failing = TopicDefinition("failing-${UUID.randomUUID()}")
  val topics = listOf(product, bulk, failing)

  fun consumers(
    consumerSettings: Map<String, Any>,
    producerSettings: PublisherSettings<String, Any>
  ): List<StoveListener> = topics.map { topic ->
    val settings = consumerSettings + mapOf(
      ConsumerConfig.GROUP_ID_CONFIG to "group-${topic.topic}",
      ConsumerConfig.CLIENT_ID_CONFIG to "consumer-${topic.topic}"
    )
    if (topic == failing) {
      ProductFailingConsumer(settings, producerSettings, topic)
    } else {
      ProductConsumer(settings, producerSettings, topic)
    }
  }
}
