package stove.quarkus.example.application

import io.opentelemetry.instrumentation.annotations.WithSpan
import jakarta.enterprise.context.ApplicationScoped
import stove.quarkus.example.infrastructure.http.SupplierHttpService
import stove.quarkus.example.infrastructure.kafka.ProductEventPublisher
import stove.quarkus.example.infrastructure.postgres.ProductRepository

@ApplicationScoped
class ProductCreator(
  private val supplierHttpService: SupplierHttpService,
  private val productRepository: ProductRepository,
  private val productEventPublisher: ProductEventPublisher
) {
  @WithSpan("ProductCreator.create")
  fun create(request: ProductCreateRequest): String {
    val supplierPermission = supplierHttpService.getSupplierPermission(request.supplierId)
    if (!supplierPermission.isAllowed) {
      return "Supplier with the given id(${request.supplierId}) is not allowed for product creation"
    }

    productRepository.save(request)
    productEventPublisher.publish(request.toProductCreatedEvent())
    return "OK"
  }
}
