package com.trendyol.stove.examples.kotlin.spring.domain.statistics

import java.time.Instant

/**
 * Read model for user order statistics.
 * Updated asynchronously when OrderCreatedEvent is consumed.
 */
data class UserOrderStatistics(
  val userId: String,
  val totalOrders: Int = 0,
  val totalAmount: Double = 0.0,
  val lastOrderAt: Instant? = null
) {
  fun addOrder(amount: Double, orderTime: Instant): UserOrderStatistics = copy(
    totalOrders = totalOrders + 1,
    totalAmount = totalAmount + amount,
    lastOrderAt = orderTime
  )
}

interface UserOrderStatisticsRepository {
  suspend fun findByUserId(userId: String): UserOrderStatistics?

  suspend fun save(statistics: UserOrderStatistics): UserOrderStatistics
}
