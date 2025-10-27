package com.trendyol.stove.examples.kotlin.ktor.domain.product

import arrow.core.Option
import com.trendyol.stove.examples.domain.product.Product

interface ProductRepository {
  suspend fun save(product: Product)

  suspend fun findById(id: String): Option<Product>
}
