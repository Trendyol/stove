package com.trendyol.stove.examples.kotlin.spring.domain.order

import com.trendyol.stove.examples.kotlin.spring.events.OrderCreatedEvent
import com.trendyol.stove.examples.kotlin.spring.events.PaymentProcessedEvent
import com.trendyol.stove.examples.kotlin.spring.infra.clients.FraudDetectedException
import com.trendyol.stove.examples.kotlin.spring.infra.clients.FraudDetectionClient
import com.trendyol.stove.examples.kotlin.spring.infra.clients.InventoryClient
import com.trendyol.stove.examples.kotlin.spring.infra.clients.InventoryNotAvailableException
import com.trendyol.stove.examples.kotlin.spring.infra.clients.PaymentClient
import com.trendyol.stove.examples.kotlin.spring.infra.clients.PaymentFailedException
import com.trendyol.stove.examples.kotlin.spring.infra.clients.PaymentResult
import com.trendyol.stove.examples.kotlin.spring.infra.kafka.OrderEventPublisher
import com.trendyol.stove.examples.kotlin.spring.infra.scheduling.EmailSchedulerService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.instrumentation.annotations.WithSpan
import org.springframework.stereotype.Service
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Order Service - The main orchestrator for order creation.
 *
 * Each step maps directly to a Stove DSL section in TheShowcase.kt:
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │ SERVICE METHOD                  │  STOVE DSL                        │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │ 1. checkFraudViaGrpc()          │  grpcMock { mockUnary(...) }      │
 * │ 2. checkInventoryViaRest()      │  wiremock { mockGet(...) }        │
 * │ 3. processPaymentViaRest()      │  wiremock { mockPost(...) }       │
 * │ 4. saveOrderToDatabase()        │  postgresql { shouldQuery(...) }  │
 * │ 5. publishEventsToKafka()       │  kafka { shouldBePublished(...) } │
 * │ 6. scheduleConfirmationEmail()  │  tasks { shouldBeExecuted(...) }  │
 * └─────────────────────────────────────────────────────────────────────┘
 */
@Service
class OrderService(
  private val orderRepository: OrderRepository,
  private val inventoryClient: InventoryClient,
  private val paymentClient: PaymentClient,
  private val fraudDetectionClient: FraudDetectionClient,
  private val eventPublisher: OrderEventPublisher,
  private val emailSchedulerService: EmailSchedulerService
) {
  /**
   * Creates a new order - the main flow that demonstrates all integrations.
   *
   * Flow:
   * 1. Check fraud via gRPC        → Stove: grpcMock {}
   * 2. Check inventory via REST    → Stove: wiremock {}
   * 3. Process payment via REST    → Stove: wiremock {}
   * 4. Save order to database      → Stove: postgresql {}
   * 5. Publish events to Kafka     → Stove: kafka {}
   * 6. Schedule confirmation email → Stove: tasks {}
   */
  @WithSpan("OrderService.createOrder")
  suspend fun createOrder(
    userId: String,
    productId: String,
    amount: Double
  ): Order {
    logger.info { "═══ Creating order for user=$userId, product=$productId, amount=$amount ═══" }
    val orderId = UUID.randomUUID().toString()

    // Step 1: Check fraud via gRPC service → grpcMock { mockUnary(...) }
    checkFraudViaGrpc(orderId, userId, amount, productId)

    // Step 2: Check inventory via REST API → wiremock { mockGet(...) }
    checkInventoryViaRest(productId)

    // Step 3: Process payment via REST API → wiremock { mockPost(...) }
    val payment = processPaymentViaRest(userId, amount)

    // Step 4: Save order to database → postgresql { shouldQuery(...) }
    val savedOrder = saveOrderToDatabase(orderId, userId, productId, amount, payment.transactionId!!)

    // Step 5: Publish events to Kafka → kafka { shouldBePublished(...) }
    publishEventsToKafka(savedOrder, payment.transactionId)

    // Step 6: Schedule confirmation email → tasks { shouldBeExecuted(...) }
    scheduleConfirmationEmail(savedOrder)

    logger.info { "═══ Order completed: id=${savedOrder.id}, status=${savedOrder.status} ═══" }
    return savedOrder
  }

  // ════════════════════════════════════════════════════════════════════════════
  // Step 1: gRPC Integration → Tested with: grpcMock { mockUnary(...) }
  // ════════════════════════════════════════════════════════════════════════════

  @WithSpan("OrderService.checkFraudViaGrpc")
  private suspend fun checkFraudViaGrpc(
    orderId: String,
    userId: String,
    amount: Double,
    productId: String
  ) {
    logger.info { "→ Checking fraud via gRPC for order=$orderId" }
    val fraudCheck = fraudDetectionClient.checkFraud(orderId, userId, amount, productId)
    if (fraudCheck.isFraudulent) {
      logger.warn { "✗ Fraud detected: reason=${fraudCheck.reason}" }
      throw FraudDetectedException(fraudCheck.reason)
    }
    logger.info { "✓ Fraud check passed: riskScore=${fraudCheck.riskScore}" }
  }

  // ════════════════════════════════════════════════════════════════════════════
  // Step 2: REST Integration (Inventory) → Tested with: wiremock { mockGet(...) }
  // ════════════════════════════════════════════════════════════════════════════

  @WithSpan("OrderService.checkInventoryViaRest")
  private suspend fun checkInventoryViaRest(productId: String) {
    logger.info { "→ Checking inventory via REST for product=$productId" }
    val inventory = inventoryClient.checkAvailability(productId)
    if (!inventory.available) {
      logger.warn { "✗ Inventory not available for product=$productId" }
      throw InventoryNotAvailableException(productId)
    }
    logger.info { "✓ Inventory available: quantity=${inventory.quantity}" }
  }

  // ════════════════════════════════════════════════════════════════════════════
  // Step 3: REST Integration (Payment) → Tested with: wiremock { mockPost(...) }
  // ════════════════════════════════════════════════════════════════════════════

  @WithSpan("OrderService.processPaymentViaRest")
  private suspend fun processPaymentViaRest(userId: String, amount: Double): PaymentResult {
    logger.info { "→ Processing payment via REST for user=$userId, amount=$amount" }
    val payment = paymentClient.charge(userId, amount)
    if (!payment.success) {
      logger.error { "✗ Payment failed: reason=${payment.errorMessage}" }
      throw PaymentFailedException(payment.errorMessage ?: "Unknown error")
    }
    logger.info { "✓ Payment successful: transactionId=${payment.transactionId}" }
    return payment
  }

  // ════════════════════════════════════════════════════════════════════════════
  // Step 4: Database → Tested with: postgresql { shouldQuery(...) }
  // ════════════════════════════════════════════════════════════════════════════

  @WithSpan("OrderService.saveOrderToDatabase")
  private suspend fun saveOrderToDatabase(
    orderId: String,
    userId: String,
    productId: String,
    amount: Double,
    transactionId: String
  ): Order {
    logger.info { "→ Saving order to database: id=$orderId" }
    val order = Order(
      id = orderId,
      userId = userId,
      productId = productId,
      amount = amount
    ).confirm(transactionId)

    val savedOrder = orderRepository.save(order)
    logger.info { "✓ Order saved: id=${savedOrder.id}, status=${savedOrder.status}" }
    return savedOrder
  }

  // ════════════════════════════════════════════════════════════════════════════
  // Step 5: Kafka → Tested with: kafka { shouldBePublished(...) }
  // ════════════════════════════════════════════════════════════════════════════

  @WithSpan("OrderService.publishEventsToKafka")
  private suspend fun publishEventsToKafka(order: Order, transactionId: String) {
    logger.info { "→ Publishing events to Kafka for order=${order.id}" }

    eventPublisher.publish(
      OrderCreatedEvent(
        orderId = order.id,
        userId = order.userId,
        productId = order.productId,
        amount = order.amount
      )
    )
    logger.info { "  ✓ OrderCreatedEvent published" }

    eventPublisher.publish(
      PaymentProcessedEvent(
        orderId = order.id,
        transactionId = transactionId,
        amount = order.amount,
        success = true
      )
    )
    logger.info { "  ✓ PaymentProcessedEvent published" }
  }

  // ════════════════════════════════════════════════════════════════════════════
  // Step 6: db-scheduler → Tested with: tasks { shouldBeExecuted(...) }
  // ════════════════════════════════════════════════════════════════════════════

  @WithSpan("OrderService.scheduleConfirmationEmail")
  private suspend fun scheduleConfirmationEmail(order: Order) {
    logger.info { "→ Scheduling confirmation email for order=${order.id}" }
    emailSchedulerService.scheduleOrderConfirmationEmail(
      orderId = order.id,
      userId = order.userId,
      amount = order.amount,
      productId = order.productId
    )
    logger.info { "  ✓ Email task scheduled" }
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
