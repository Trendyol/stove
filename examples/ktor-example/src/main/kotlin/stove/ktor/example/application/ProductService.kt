package stove.ktor.example.application

import org.apache.kafka.clients.producer.*
import stove.ktor.example.domain.*
import stove.ktor.example.infrastructure.FeatureToggleClient
import stove.ktor.example.infrastructure.PricingClient
import java.time.Duration
import kotlin.coroutines.*

class ProductService(
  private val repository: ProductRepository,
  private val lockProvider: LockProvider,
  private val kafkaProducer: KafkaProducer<String, Any>,
  private val featureToggleClient: FeatureToggleClient,
  private val pricingClient: PricingClient
) {
  companion object {
    private const val DURATION = 30L
    private const val FEATURE_PRODUCT_UPDATE = "product-update-enabled"
  }

  suspend fun update(id: Int, request: UpdateProductRequest) {
    // 1. Check if product update feature is enabled (Feature Toggle Service)
    val featureCheck = featureToggleClient.isFeatureEnabled(
      featureName = FEATURE_PRODUCT_UPDATE,
      context = request.userId ?: "anonymous"
    )

    if (!featureCheck.enabled) {
      error("Product update feature is currently disabled")
    }

    // 2. Get pricing information (Pricing Service)
    val priceInfo = pricingClient.calculatePrice(
      productId = id.toString(),
      quantity = 1,
      currency = "USD",
      customerTier = "standard"
    )

    val acquireLock = lockProvider.acquireLock(::ProductService.name, Duration.ofSeconds(DURATION))

    if (!acquireLock) {
      print("lock could not be acquired")
      return
    }

    try {
      repository.transaction {
        val product = it.findById(id)
        product.name = request.name
        it.update(product)
      }

      // Publish event with price info
      suspendCoroutine {
        kafkaProducer
          .send(
            ProducerRecord(
              "product",
              id.toString(),
              DomainEvents.ProductUpdated(id, request.name, priceInfo.finalPrice)
            )
          ) { _, exception ->
            if (exception != null) {
              it.resumeWithException(exception)
            } else {
              it.resume(Unit)
            }
          }
      }
    } finally {
      lockProvider.releaseLock(::ProductService.name)
    }
  }

  /**
   * Get product with calculated price.
   */
  suspend fun getProductWithPrice(productId: Int, customerId: String): ProductWithPrice {
    val product = repository.findById(productId)

    // Get discount from Pricing Service
    val discount = pricingClient.getDiscount(customerId, "electronics")

    // Calculate final price
    val price = pricingClient.calculatePrice(
      productId = productId.toString(),
      quantity = 1,
      currency = "USD",
      customerTier = if (discount.isApplicable) "premium" else "standard"
    )

    return ProductWithPrice(
      id = product.id,
      name = product.name,
      basePrice = price.basePrice,
      discount = price.discount,
      finalPrice = price.finalPrice
    )
  }
}

data class ProductWithPrice(
  val id: Int,
  val name: String,
  val basePrice: Double,
  val discount: Double,
  val finalPrice: Double
)
