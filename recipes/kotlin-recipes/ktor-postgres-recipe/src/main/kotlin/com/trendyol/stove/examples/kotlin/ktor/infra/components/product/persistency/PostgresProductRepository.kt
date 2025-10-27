package com.trendyol.stove.examples.kotlin.ktor.infra.components.product.persistency

import arrow.core.*
import com.trendyol.stove.examples.domain.product.Product
import com.trendyol.stove.examples.kotlin.ktor.domain.product.ProductRepository
import com.trendyol.stove.examples.kotlin.ktor.infra.boilerplate.kafka.KafkaDomainEventPublisher
import kotlinx.coroutines.flow.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

class PostgresProductRepository(
  private val eventPublisher: KafkaDomainEventPublisher
) : ProductRepository {
  override suspend fun save(product: Product) = suspendTransaction {
    if (product.isNew) {
      saveInternal(product)
    } else {
      update(product)
    }
    eventPublisher.publishFor(product)
  }

  private suspend fun saveInternal(product: Product) {
    ProductTable.insert {
      it[id] = product.id
      it[name] = product.name
      it[price] = product.price
      it[categoryId] = product.categoryId
    }
  }

  private suspend fun update(product: Product) {
    val updatedRows = ProductTable.update({ ProductTable.id eq product.id }) {
      it[name] = product.name
      it[price] = product.price
      it[categoryId] = product.categoryId
      it[version] = product.version
    }
    if (updatedRows == 0) {
      throw RuntimeException("Product with id ${product.id} was updated concurrently.")
    }
  }

  override suspend fun findById(
    id: String
  ): Option<Product> = suspendTransaction {
    ProductTable
      .selectAll()
      .where { ProductTable.id eq id }
      .map {
        Product.fromPersistency(
          it[ProductTable.id],
          it[ProductTable.name],
          it[ProductTable.price],
          it[ProductTable.categoryId],
          it[ProductTable.version]
        )
      }.firstOrNull()
      .toOption()
  }
}
