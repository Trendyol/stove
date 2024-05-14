package com.trendyol.stove.examples.java.spring.infra.boilerplate.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trendyol.stove.examples.domain.ddd.AggregateRoot;
import com.trendyol.stove.examples.domain.ddd.EventPublisher;
import java.util.stream.Stream;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaDomainEventPublisher implements EventPublisher {
  private final KafkaTemplate<String, Object> template;
  private final TopicResolver topicResolver;
  private final ObjectMapper objectMapper;
  private final Logger logger = org.slf4j.LoggerFactory.getLogger(KafkaDomainEventPublisher.class);

  public KafkaDomainEventPublisher(
      KafkaTemplate<String, Object> template,
      TopicResolver topicResolver,
      @Qualifier("objectMapper") ObjectMapper objectMapper) {
    this.template = template;
    this.topicResolver = topicResolver;
    this.objectMapper = objectMapper;
  }

  @Override
  public <TId> void publishFor(AggregateRoot<TId> aggregateRoot) {
    mapEventsToProducerRecords(aggregateRoot).forEach(template::send);
  }

  private <TId> Stream<ProducerRecord<String, Object>> mapEventsToProducerRecords(
      AggregateRoot<TId> aggregateRoot) {
    return aggregateRoot.domainEvents().stream()
        .map(
            event -> {
              var topic = topicResolver.resolve(aggregateRoot.getAggregateName());
              try {
                logger.info("Publishing event {} to topic {}", event, topic.getName());
                return new ProducerRecord<>(
                    topic.getName(),
                    aggregateRoot.getIdAsString(),
                    objectMapper.writeValueAsString(event));
              } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
              }
            });
  }
}
