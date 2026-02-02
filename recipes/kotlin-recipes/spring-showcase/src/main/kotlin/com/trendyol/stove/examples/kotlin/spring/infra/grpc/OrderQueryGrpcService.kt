package com.trendyol.stove.examples.kotlin.spring.infra.grpc

import com.trendyol.stove.examples.kotlin.spring.domain.order.OrderRepository
import com.trendyol.stove.examples.kotlin.spring.grpc.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.instrumentation.annotations.WithSpan
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * gRPC service implementation for querying orders.
 *
 * This demonstrates exposing our application's functionality via gRPC,
 * which Stove can test using the `stove-grpc` module.
 */
@Service
class OrderQueryGrpcService(
  private val orderRepository: OrderRepository
) : OrderQueryServiceGrpcKt.OrderQueryServiceCoroutineImplBase() {

  @WithSpan("OrderQueryGrpcService.getOrder")
  override suspend fun getOrder(request: GetOrderRequest): GetOrderResponse {
    logger.info { "gRPC: GetOrder called for id=${request.orderId}" }

    val order = orderRepository.findById(request.orderId)

    return if (order != null) {
      GetOrderResponse.newBuilder()
        .setFound(true)
        .setOrder(order.toProto())
        .build()
    } else {
      GetOrderResponse.newBuilder()
        .setFound(false)
        .build()
    }
  }

  @WithSpan("OrderQueryGrpcService.getOrdersByUser")
  override suspend fun getOrdersByUser(request: GetOrdersByUserRequest): GetOrdersResponse {
    logger.info { "gRPC: GetOrdersByUser called for userId=${request.userId}" }

    val orders = orderRepository.findByUserId(request.userId)

    return GetOrdersResponse.newBuilder()
      .addAllOrders(orders.map { it.toProto() })
      .build()
  }

  private fun com.trendyol.stove.examples.kotlin.spring.domain.order.Order.toProto(): OrderProto =
    OrderProto.newBuilder()
      .setId(id)
      .setUserId(userId)
      .setProductId(productId)
      .setAmount(amount)
      .setStatus(status.name)
      .setPaymentTransactionId(paymentTransactionId ?: "")
      .setCreatedAt(createdAt.toEpochMilli())
      .build()
}
