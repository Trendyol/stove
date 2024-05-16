package com.trendyol.stove.examples.kotlin.ktor.infra.boilerplate.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.examples.domain.ddd.*
import io.github.nomisRev.kafka.publisher.KafkaPublisher
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.*

class KafkaDomainEventPublisher(
  private val publisher: KafkaPublisher<String, Any>,
  private val topicResolver: TopicResolver,
  private val objectMapper: ObjectMapper
) : EventPublisher {
  private val logger: Logger = LoggerFactory.getLogger(KafkaDomainEventPublisher::class.java)

  override fun <TId> publishFor(aggregateRoot: AggregateRoot<TId>) = runBlocking {
    mapEventsToProducerRecords(aggregateRoot)
      .forEach { record -> publisher.publishScope { offer(record) } }
  }

  private fun <TId> mapEventsToProducerRecords(
    aggregateRoot: AggregateRoot<TId>
  ): List<ProducerRecord<String, Any>> = aggregateRoot.domainEvents()
    .map { event ->
      val topic: Topic = topicResolver(aggregateRoot.aggregateName)
      logger.info("Publishing event {} to topic {}", event, topic.name)
      ProducerRecord<String, Any>(
        topic.name,
        aggregateRoot.idAsString,
        objectMapper.writeValueAsString(event)
      )
    }
}
