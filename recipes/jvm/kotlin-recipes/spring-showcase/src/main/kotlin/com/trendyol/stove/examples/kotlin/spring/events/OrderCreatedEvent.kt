package com.trendyol.stove.examples.kotlin.spring.events

import java.time.Instant

data class OrderCreatedEvent(
  val orderId: String,
  val userId: String,
  val productId: String,
  val amount: Double,
  val createdAt: Instant = Instant.now()
)
