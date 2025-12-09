package stove.spring.example4x.application.handlers

import org.springframework.stereotype.Service
import stove.spring.example4x.infrastructure.api.ProductCreateRequest

@Service
class ProductCreator {
  suspend fun create(request: ProductCreateRequest) {
    // In a real application, this would persist the product
    println("Creating product: ${request.name} with id ${request.id}")
  }
}

data class ProductCreatedEvent(
  val id: Long,
  val name: String,
  val supplierId: Long
)
