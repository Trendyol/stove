package stove.micronaut.example.application.repository

import stove.micronaut.example.application.domain.Product

interface ProductRepository {
  suspend fun save(product: Product): Product

  suspend fun findById(id: Long): Product?
}
