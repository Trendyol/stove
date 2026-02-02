package com.trendyol.stove.examples.kotlin.spring.infra.clients

import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.instrumentation.annotations.WithSpan
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

private val logger = KotlinLogging.logger {}

@Component
class PaymentClient(
  @param:Value("\${external-apis.payment.url}") private val baseUrl: String
) {
  private val webClient = WebClient.builder()
    .baseUrl(baseUrl)
    .build()

  @WithSpan("PaymentClient.charge")
  suspend fun charge(userId: String, amount: Double): PaymentResult {
    logger.info { "Processing payment for user=$userId, amount=$amount" }
    return webClient.post()
      .uri("/payments/charge")
      .bodyValue(PaymentRequest(userId, amount))
      .retrieve()
      .awaitBody<PaymentResult>()
  }
}

data class PaymentRequest(
  val userId: String,
  val amount: Double
)

data class PaymentResult(
  val success: Boolean,
  val transactionId: String? = null,
  val amount: Double = 0.0,
  val errorMessage: String? = null
)

class PaymentFailedException(message: String) : RuntimeException("Payment failed: $message")
