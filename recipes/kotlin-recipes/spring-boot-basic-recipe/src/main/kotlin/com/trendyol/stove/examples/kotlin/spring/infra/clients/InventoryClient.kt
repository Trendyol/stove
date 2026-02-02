package com.trendyol.stove.examples.kotlin.spring.infra.clients

import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.instrumentation.annotations.WithSpan
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

private val logger = KotlinLogging.logger {}

@Component
class InventoryClient(
  @param:Value("\${external-apis.inventory.url}") private val baseUrl: String
) {
  private val webClient = WebClient.builder()
    .baseUrl(baseUrl)
    .build()

  @WithSpan("InventoryClient.checkAvailability")
  suspend fun checkAvailability(productId: String): InventoryResponse {
    logger.info { "Checking inventory for product=$productId" }
    return webClient.get()
      .uri("/inventory/$productId")
      .retrieve()
      .awaitBody<InventoryResponse>()
  }
}

data class InventoryResponse(
  val productId: String,
  val available: Boolean,
  val quantity: Int = 0
)

class InventoryNotAvailableException(productId: String) :
  RuntimeException("Inventory not available for product: $productId")
