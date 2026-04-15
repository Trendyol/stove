package com.trendyol.stove.examples.java.spring.infra.boilerplate.kafka;

import com.trendyol.stove.examples.domain.ddd.AggregateRoot;
import com.trendyol.stove.examples.domain.ddd.EventPublisher;
import java.util.stream.Stream;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaDomainEventPublisher implements EventPublisher {
  private final KafkaTemplate<String, Object> template;
  private final TopicResolver topicResolver;
  private final Logger logger = org.slf4j.LoggerFactory.getLogger(KafkaDomainEventPublisher.class);

  public KafkaDomainEventPublisher(
      KafkaTemplate<String, Object> template, TopicResolver topicResolver) {
    this.template = template;
    this.topicResolver = topicResolver;
  }

  @Override
  public <TId> void publishFor(AggregateRoot<TId> aggregateRoot) {
    mapEventsToProducerRecords(aggregateRoot).forEach(template::send);
  }

  private <TId> Stream<ProducerRecord<String, Object>> mapEventsToProducerRecords(
      AggregateRoot<TId> aggregateRoot) {
    return aggregateRoot.domainEvents().stream().map(event -> {
      var topic = topicResolver.resolve(aggregateRoot.getAggregateName());
      logger.info("Publishing event {} to topic {}", event, topic.getName());
      return new ProducerRecord<>(topic.getName(), aggregateRoot.getIdAsString(), event);
    });
  }
}
