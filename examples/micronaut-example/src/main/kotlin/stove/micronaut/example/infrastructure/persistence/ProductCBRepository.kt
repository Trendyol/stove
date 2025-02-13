package stove.micronaut.example.infrastructure.persistence

import com.couchbase.client.java.Collection
import jakarta.inject.Singleton
import stove.micronaut.example.application.domain.Product
import stove.micronaut.example.application.repository.ProductRepository

@Singleton
class ProductCBRepository(
  private val productCouchbaseCollection: Collection
) : ProductRepository {
  override suspend fun save(product: Product): Product {
    productCouchbaseCollection.insert(product.id, product)
    return product
  }

  override suspend fun findById(id: Long): Product? = productCouchbaseCollection.get(id.toString()).contentAs(Product::class.java)
}
