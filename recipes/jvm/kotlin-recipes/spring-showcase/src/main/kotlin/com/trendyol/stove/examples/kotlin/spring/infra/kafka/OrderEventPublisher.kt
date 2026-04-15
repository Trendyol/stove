package com.trendyol.stove.examples.kotlin.spring.infra.kafka

import com.trendyol.stove.examples.kotlin.spring.events.OrderCreatedEvent
import com.trendyol.stove.examples.kotlin.spring.events.PaymentProcessedEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.instrumentation.annotations.WithSpan
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class OrderEventPublisher(
  private val kafkaTemplate: KafkaTemplate<String, Any>,
  @param:Value("\${kafka.topics.orders-created}") private val ordersCreatedTopic: String,
  @param:Value("\${kafka.topics.payments-processed}") private val paymentsProcessedTopic: String
) {
  @WithSpan("OrderEventPublisher.publishOrderCreated")
  fun publish(event: OrderCreatedEvent) {
    logger.info { "Publishing OrderCreatedEvent: orderId=${event.orderId}" }
    kafkaTemplate.send(ordersCreatedTopic, event.orderId, event)
  }

  @WithSpan("OrderEventPublisher.publishPaymentProcessed")
  fun publish(event: PaymentProcessedEvent) {
    logger.info { "Publishing PaymentProcessedEvent: orderId=${event.orderId}" }
    kafkaTemplate.send(paymentsProcessedTopic, event.orderId, event)
  }
}
