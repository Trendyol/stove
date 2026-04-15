package com.trendyol.stove.examples.kotlin.spring.events

import java.time.Instant

data class PaymentProcessedEvent(
  val orderId: String,
  val transactionId: String,
  val amount: Double,
  val success: Boolean,
  val createdAt: Instant = Instant.now()
)
