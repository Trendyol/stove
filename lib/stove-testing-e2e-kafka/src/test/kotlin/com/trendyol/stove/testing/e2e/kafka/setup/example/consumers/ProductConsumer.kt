package com.trendyol.stove.testing.e2e.kafka.setup.example.consumers

import com.trendyol.stove.testing.e2e.kafka.setup.example.KafkaTestShared.TopicDefinition
import com.trendyol.stove.testing.e2e.kafka.setup.example.StoveListener
import org.apache.kafka.clients.consumer.ConsumerRecord

class ProductConsumer(
    consumerSettings: Map<String, Any>,
    producerSettings: Map<String, Any>,
) : StoveListener(consumerSettings, producerSettings) {

    override val topicDefinition: TopicDefinition = TopicDefinition("product", "product.retry", "product.error")
    override suspend fun listen(record: ConsumerRecord<String, String>) {
    }
}
