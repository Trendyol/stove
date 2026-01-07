package com.trendyol.stove.ktor

import java.math.BigDecimal
import java.time.Instant

/**
 * Common test domain classes for Ktor bridge tests.
 */

fun interface GetUtcNow {
  companion object {
    val frozenTime: Instant = Instant.parse("2021-01-01T00:00:00Z")
  }

  operator fun invoke(): Instant
}

class SystemTimeGetUtcNow : GetUtcNow {
  override fun invoke(): Instant = GetUtcNow.frozenTime
}

class ExampleService(
  private val getUtcNow: GetUtcNow
) {
  fun whatIsTheTime(): Instant = getUtcNow()
}

data class TestConfig(
  val message: String = "Hello from Stove!"
)

/**
 * Domain classes for testing multi-instance resolution.
 */
data class Order(
  val id: String,
  val amount: BigDecimal
)

data class PaymentResult(
  val provider: String,
  val success: Boolean
)

interface PaymentService {
  val providerName: String

  fun pay(order: Order): PaymentResult
}

class StripePaymentService : PaymentService {
  override val providerName = "Stripe"

  override fun pay(order: Order) = PaymentResult(providerName, true)
}

class PayPalPaymentService : PaymentService {
  override val providerName = "PayPal"

  override fun pay(order: Order) = PaymentResult(providerName, true)
}

class SquarePaymentService : PaymentService {
  override val providerName = "Square"

  override fun pay(order: Order) = PaymentResult(providerName, true)
}
