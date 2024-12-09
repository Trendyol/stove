package stove.micronaut.example.application.services

import jakarta.inject.Singleton
import stove.micronaut.example.application.domain.Product
import stove.micronaut.example.application.repository.ProductRepository
import stove.micronaut.example.infrastructure.http.SupplierHttpService

@Singleton
class ProductService(
  private val productRepository: ProductRepository,
  private val supplierHttpService: SupplierHttpService
) {
  suspend fun createProduct(id: String, productName: String, supplierId: Long): Product {
    val supplier = supplierHttpService.getSupplierPermission(supplierId)
    val product = Product.new(id, productName, supplierId, supplier!!.isBlacklisted)
    productRepository.save(product)
    return product
  }
}
