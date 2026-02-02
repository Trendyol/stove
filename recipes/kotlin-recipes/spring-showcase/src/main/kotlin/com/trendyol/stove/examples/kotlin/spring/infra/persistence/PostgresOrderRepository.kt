package com.trendyol.stove.examples.kotlin.spring.infra.persistence

import com.trendyol.stove.examples.kotlin.spring.domain.order.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.instrumentation.annotations.SpanAttribute
import io.opentelemetry.instrumentation.annotations.WithSpan
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.*
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.bind
import org.springframework.stereotype.Repository
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Repository
class PostgresOrderRepository(
  private val databaseClient: DatabaseClient
) : OrderRepository {
  @WithSpan("PostgresOrderRepository.save")
  override suspend fun save(order: Order): Order {
    logger.info { "Saving order: id=${order.id}" }

    // ══════════════════════════════════════════════════════════════════════════
    // 🐛 DEMO BUG: Uncomment below to simulate a deep production bug
    // This bug only triggers for high-value orders (> $1000), making it hard
    // to catch in simple unit tests. The trace will show exactly where it fails!
    // ══════════════════════════════════════════════════════════════════════════
    // validateOrderAmount(order)

    databaseClient
      .sql(
        """
      INSERT INTO orders (id, user_id, product_id, amount, status, payment_transaction_id, created_at)
      VALUES (:id, :userId, :productId, :amount, :status, :paymentTransactionId, :createdAt)
      ON CONFLICT (id) DO UPDATE SET
        status = :status,
        payment_transaction_id = :paymentTransactionId
        """.trimIndent()
      ).bind("id", order.id)
      .bind("userId", order.userId)
      .bind("productId", order.productId)
      .bind("amount", order.amount)
      .bind("status", order.status.name)
      .bind("paymentTransactionId", order.paymentTransactionId)
      .bind("createdAt", order.createdAt)
      .fetch()
      .rowsUpdated()
      .awaitSingle()

    return order
  }

  @WithSpan("PostgresOrderRepository.findById")
  override suspend fun findById(
    @SpanAttribute("order.id") id: String
  ): Order? = databaseClient
    .sql(
      """
      SELECT id, user_id, product_id, amount, status, payment_transaction_id, created_at
      FROM orders
      WHERE id = :id
      """.trimIndent()
    ).bind("id", id)
    .map { row, _ -> mapToOrder(row) }
    .first()
    .awaitFirstOrNull()

  @WithSpan("PostgresOrderRepository.findByUserId")
  override suspend fun findByUserId(
    @SpanAttribute("order.userId") userId: String
  ): List<Order> = databaseClient
    .sql(
      """
      SELECT id, user_id, product_id, amount, status, payment_transaction_id, created_at
      FROM orders
      WHERE user_id = :userId
      """.trimIndent()
    ).bind("userId", userId)
    .map { row, _ -> mapToOrder(row) }
    .all()
    .asFlow()
    .toList()

  private fun mapToOrder(row: io.r2dbc.spi.Row): Order = Order(
    id = row.get("id", String::class.java)!!,
    userId = row.get("user_id", String::class.java)!!,
    productId = row.get("product_id", String::class.java)!!,
    amount = (row.get("amount", java.math.BigDecimal::class.java)!!).toDouble(),
    status = OrderStatus.valueOf(row.get("status", String::class.java)!!),
    paymentTransactionId = row.get("payment_transaction_id", String::class.java),
    createdAt = row.get("created_at", Instant::class.java)!!
  )

  // ══════════════════════════════════════════════════════════════════════════
  // 🐛 DEMO BUG: Simulates a bug deep in the persistence layer
  // In production, this might be: connection pool exhaustion, constraint
  // violation, or business rule that wasn't properly documented.
  // ══════════════════════════════════════════════════════════════════════════
  @Suppress("UnusedPrivateMember", "ThrowsCount")
  private fun validateOrderAmount(order: Order) {
    if (order.amount > 1000) {
      // Simulating a bug: maybe the payment gateway has an undocumented limit,
      // or there's a database constraint we didn't know about
      throw OrderPersistenceException(
        "Failed to persist order ${order.id}: amount exceeds internal threshold"
      )
    }
  }
}

class OrderPersistenceException(
  message: String
) : RuntimeException(message)
