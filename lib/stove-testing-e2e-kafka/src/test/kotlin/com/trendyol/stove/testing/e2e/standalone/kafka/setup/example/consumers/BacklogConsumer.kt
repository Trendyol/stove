package com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.consumers

import com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.KafkaTestShared.TopicDefinition
import com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.StoveListener
import org.apache.kafka.clients.consumer.ConsumerRecord

class BacklogConsumer(
    consumerSettings: Map<String, Any>,
    producerSettings: Map<String, Any>
) : StoveListener(consumerSettings, producerSettings) {

    override val topicDefinition: TopicDefinition = TopicDefinition("backlog", "backlog.retry", "backlog.error")
    override suspend fun listen(record: ConsumerRecord<String, String>) {
    }
}
