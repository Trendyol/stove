package com.trendyol.stove.examples.kotlin.spring.infra.kafka

import com.trendyol.stove.examples.kotlin.spring.domain.statistics.*
import com.trendyol.stove.examples.kotlin.spring.events.OrderCreatedEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.instrumentation.annotations.WithSpan
import kotlinx.coroutines.runBlocking
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Kafka listener that consumes OrderCreatedEvent and updates the read model.
 *
 * This demonstrates the Event Sourcing / CQRS pattern where:
 * - Commands create orders (write model)
 * - Events update statistics (read model)
 *
 * In the showcase test, we verify:
 * 1. The event was consumed (shouldBeConsumed)
 * 2. The side effect happened (statistics were updated)
 */
@Component
class OrderCreatedEventListener(
  private val statisticsRepository: UserOrderStatisticsRepository
) {
  @KafkaListener(
    topics = ["\${kafka.topics.orders-created}"],
    groupId = "order-statistics-updater",
    containerFactory = "kafkaListenerContainerFactory"
  )
  @WithSpan("OrderCreatedEventListener.onOrderCreated")
  fun onOrderCreated(event: OrderCreatedEvent) = runBlocking {
    logger.info { "Received OrderCreatedEvent: orderId=${event.orderId}, userId=${event.userId}" }
    updateStatistics(event)
    logger.info { "Statistics updated for user=${event.userId}" }
  }

  private suspend fun updateStatistics(event: OrderCreatedEvent) {
    val existing = statisticsRepository.findByUserId(event.userId)
    val updated = (existing ?: UserOrderStatistics(userId = event.userId))
      .addOrder(event.amount, event.createdAt)

    statisticsRepository.save(updated)
  }
}
