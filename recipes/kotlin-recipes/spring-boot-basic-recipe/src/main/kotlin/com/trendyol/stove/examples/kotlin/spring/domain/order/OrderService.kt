package com.trendyol.stove.examples.kotlin.spring.domain.order

import com.trendyol.stove.examples.kotlin.spring.events.OrderCreatedEvent
import com.trendyol.stove.examples.kotlin.spring.events.PaymentProcessedEvent
import com.trendyol.stove.examples.kotlin.spring.infra.clients.FraudDetectedException
import com.trendyol.stove.examples.kotlin.spring.infra.clients.FraudDetectionClient
import com.trendyol.stove.examples.kotlin.spring.infra.clients.InventoryClient
import com.trendyol.stove.examples.kotlin.spring.infra.clients.InventoryNotAvailableException
import com.trendyol.stove.examples.kotlin.spring.infra.clients.PaymentClient
import com.trendyol.stove.examples.kotlin.spring.infra.clients.PaymentFailedException
import com.trendyol.stove.examples.kotlin.spring.infra.kafka.OrderEventPublisher
import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.instrumentation.annotations.WithSpan
import org.springframework.stereotype.Service
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class OrderService(
  private val orderRepository: OrderRepository,
  private val inventoryClient: InventoryClient,
  private val paymentClient: PaymentClient,
  private val fraudDetectionClient: FraudDetectionClient,
  private val eventPublisher: OrderEventPublisher
) {

  @WithSpan("OrderService.createOrder")
  suspend fun createOrder(
    userId: String,
    productId: String,
    amount: Double
  ): Order {
    logger.info { "Creating order for user=$userId, product=$productId, amount=$amount" }
    val orderId = UUID.randomUUID().toString()

    // Check fraud via gRPC service
    val fraudCheck = fraudDetectionClient.checkFraud(orderId, userId, amount, productId)
    if (fraudCheck.isFraudulent) {
      logger.warn { "Fraud detected for order=$orderId, reason=${fraudCheck.reason}" }
      throw FraudDetectedException(fraudCheck.reason)
    }
    logger.info { "Fraud check passed: riskScore=${fraudCheck.riskScore}" }

    // Check inventory
    val inventory = inventoryClient.checkAvailability(productId)
    if (!inventory.available) {
      logger.warn { "Inventory not available for product=$productId" }
      throw InventoryNotAvailableException(productId)
    }

    // Process payment
    val payment = paymentClient.charge(userId, amount)
    if (!payment.success) {
      logger.error { "Payment failed for user=$userId, reason=${payment.errorMessage}" }
      throw PaymentFailedException(payment.errorMessage ?: "Unknown error")
    }

    // Create and save order
    val order = Order(
      id = orderId,
      userId = userId,
      productId = productId,
      amount = amount
    ).confirm(payment.transactionId!!)

    val savedOrder = orderRepository.save(order)
    logger.info { "Order created: id=${savedOrder.id}, status=${savedOrder.status}" }

    // Publish events
    eventPublisher.publish(
      OrderCreatedEvent(
        orderId = savedOrder.id,
        userId = savedOrder.userId,
        productId = savedOrder.productId,
        amount = savedOrder.amount
      )
    )

    eventPublisher.publish(
      PaymentProcessedEvent(
        orderId = savedOrder.id,
        transactionId = payment.transactionId,
        amount = savedOrder.amount,
        success = true
      )
    )

    return savedOrder
  }

  @WithSpan("OrderService.getOrder")
  suspend fun getOrder(id: String): Order? = orderRepository.findById(id)

  @WithSpan("OrderService.getOrderByUserId")
  suspend fun getOrderByUserId(userId: String): Order? =
    orderRepository.findByUserId(userId).firstOrNull()

  @WithSpan("OrderService.getOrdersByUserId")
  suspend fun getOrdersByUserId(userId: String): List<Order> =
    orderRepository.findByUserId(userId)
}
