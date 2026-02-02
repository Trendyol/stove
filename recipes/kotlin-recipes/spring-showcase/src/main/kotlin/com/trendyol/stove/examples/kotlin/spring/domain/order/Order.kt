package com.trendyol.stove.examples.kotlin.spring.domain.order

import java.time.Instant
import java.util.UUID

data class Order(
  val id: String = UUID.randomUUID().toString(),
  val userId: String,
  val productId: String,
  val amount: Double,
  val status: OrderStatus = OrderStatus.PENDING,
  val paymentTransactionId: String? = null,
  val createdAt: Instant = Instant.now()
) {
  fun confirm(paymentTransactionId: String): Order = copy(
    status = OrderStatus.CONFIRMED,
    paymentTransactionId = paymentTransactionId
  )

  fun fail(): Order = copy(status = OrderStatus.FAILED)
}

enum class OrderStatus {
  PENDING,
  CONFIRMED,
  FAILED
}
