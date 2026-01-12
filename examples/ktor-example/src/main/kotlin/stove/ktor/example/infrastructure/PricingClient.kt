package stove.ktor.example.infrastructure

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import stove.ktor.example.grpc.*
import java.util.concurrent.TimeUnit

/**
 * gRPC client for the external Pricing service.
 *
 * This client calls a hypothetical external Pricing microservice to:
 * - Calculate prices for products
 * - Get applicable discounts for customers
 */
class PricingClient(
  private val host: String,
  private val port: Int
) : AutoCloseable {
  private val channel: ManagedChannel = ManagedChannelBuilder
    .forAddress(host, port)
    .usePlaintext()
    .build()

  private val stub: PricingServiceGrpcKt.PricingServiceCoroutineStub =
    PricingServiceGrpcKt.PricingServiceCoroutineStub(channel)

  /**
   * Calculate price for a product.
   */
  suspend fun calculatePrice(
    productId: String,
    quantity: Int,
    currency: String = "USD",
    customerTier: String = "standard"
  ): CalculatePriceResponse {
    val request = calculatePriceRequest {
      this.productId = productId
      this.quantity = quantity
      this.currency = currency
      this.customerTier = customerTier
    }
    return stub.calculatePrice(request)
  }

  /**
   * Get applicable discount for a customer.
   */
  suspend fun getDiscount(customerId: String, productCategory: String): GetDiscountResponse {
    val request = getDiscountRequest {
      this.customerId = customerId
      this.productCategory = productCategory
    }
    return stub.getDiscount(request)
  }

  @Suppress("MagicNumber")
  override fun close() {
    channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
  }
}
