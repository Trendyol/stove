package com.trendyol.stove.examples.java.spring.infra.boilerplate.kafka;

import org.springframework.stereotype.Component;

@Component
public class TopicResolver {
  private final KafkaConfiguration kafkaConfiguration;

  public TopicResolver(KafkaConfiguration kafkaConfiguration) {
    this.kafkaConfiguration = kafkaConfiguration;
  }

  public Topic resolve(String aggregateName) {
    return kafkaConfiguration.getTopics().get(aggregateName);
  }
}
