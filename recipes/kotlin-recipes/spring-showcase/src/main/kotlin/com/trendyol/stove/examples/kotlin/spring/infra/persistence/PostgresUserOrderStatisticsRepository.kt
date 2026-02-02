package com.trendyol.stove.examples.kotlin.spring.infra.persistence

import com.trendyol.stove.examples.kotlin.spring.domain.statistics.UserOrderStatistics
import com.trendyol.stove.examples.kotlin.spring.domain.statistics.UserOrderStatisticsRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.instrumentation.annotations.WithSpan
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.bind
import org.springframework.stereotype.Repository
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Repository
class PostgresUserOrderStatisticsRepository(
  private val databaseClient: DatabaseClient
) : UserOrderStatisticsRepository {
  @WithSpan("UserOrderStatisticsRepository.findByUserId")
  override suspend fun findByUserId(userId: String): UserOrderStatistics? = databaseClient
    .sql(
      """
      SELECT user_id, total_orders, total_amount, last_order_at
      FROM user_order_statistics
      WHERE user_id = :userId
      """.trimIndent()
    ).bind("userId", userId)
    .map { row, _ ->
      @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
      UserOrderStatistics(
        userId = row.get("user_id", String::class.java)!!,
        totalOrders = (row.get("total_orders", java.lang.Integer::class.java) as Int?) ?: 0,
        totalAmount = row.get("total_amount", java.math.BigDecimal::class.java)!!.toDouble(),
        lastOrderAt = row.get("last_order_at", Instant::class.java)
      )
    }.first()
    .awaitFirstOrNull()

  @WithSpan("UserOrderStatisticsRepository.save")
  override suspend fun save(statistics: UserOrderStatistics): UserOrderStatistics {
    logger.info { "Saving user statistics: userId=${statistics.userId}, totalOrders=${statistics.totalOrders}" }

    databaseClient
      .sql(
        """
        INSERT INTO user_order_statistics (user_id, total_orders, total_amount, last_order_at)
        VALUES (:userId, :totalOrders, :totalAmount, :lastOrderAt)
        ON CONFLICT (user_id) DO UPDATE SET
          total_orders = :totalOrders,
          total_amount = :totalAmount,
          last_order_at = :lastOrderAt
        """.trimIndent()
      ).bind("userId", statistics.userId)
      .bind("totalOrders", statistics.totalOrders)
      .bind("totalAmount", statistics.totalAmount)
      .bind("lastOrderAt", statistics.lastOrderAt)
      .fetch()
      .rowsUpdated()
      .awaitSingle()

    return statistics
  }
}
