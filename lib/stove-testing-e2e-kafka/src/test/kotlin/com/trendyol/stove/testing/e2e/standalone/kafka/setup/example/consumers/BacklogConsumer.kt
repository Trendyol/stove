package com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.consumers

import com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.KafkaTestShared.TopicDefinition
import com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.StoveListener
import io.github.nomisRev.kafka.publisher.PublisherSettings
import org.apache.kafka.clients.consumer.ConsumerRecord

class BacklogConsumer(
    consumerSettings: Map<String, Any>,
    producerSettings: PublisherSettings<String, String>
) : StoveListener(consumerSettings, producerSettings) {
    override val topicDefinition: TopicDefinition = TopicDefinition("backlog", "backlog.retry", "backlog.error")

    override suspend fun listen(record: ConsumerRecord<String, String>) {
    }
}
