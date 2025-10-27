package com.trendyol.stove.examples.kotlin.ktor.infra.components.product.messaging

import com.trendyol.stove.examples.kotlin.ktor.infra.boilerplate.kafka.*
import io.github.nomisRev.kafka.receiver.KafkaReceiver
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.kafka.clients.consumer.ConsumerRecord

class ProductAggregateRootEventsConsumer(
  topicResolver: TopicResolver,
  kafkaReceiver: KafkaReceiver<String, Any>,
  topic: Topic = topicResolver("product")
) : ConsumerSupervisor<String, Any>(kafkaReceiver, topic.concurrency) {
  private val logger = KotlinLogging.logger { }
  override val topics: List<String> = listOf(topic.name, topic.retry)

  override suspend fun consume(record: ConsumerRecord<String, Any>) {
    logger.info { "consumed record: $record" }
  }

  override fun handleError(e: Exception, record: ConsumerRecord<String, Any>) {
    logger.error(e) { "Error while processing record: $record" }
  }
}
