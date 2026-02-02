package com.trendyol.stove.examples.kotlin.spring.domain.order

interface OrderRepository {
  suspend fun save(order: Order): Order

  suspend fun findById(id: String): Order?

  suspend fun findByUserId(userId: String): List<Order>
}
