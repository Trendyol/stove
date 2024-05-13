package com.trendyol.stove.examples.java.spring.application.product.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ProductEventHandlerListener {

  @KafkaListener(topics = {"${kafka.topics.product.name}"})
  public void listen(ConsumerRecord<?, ?> event) {
    System.out.println("Received event: " + event);
  }
}
