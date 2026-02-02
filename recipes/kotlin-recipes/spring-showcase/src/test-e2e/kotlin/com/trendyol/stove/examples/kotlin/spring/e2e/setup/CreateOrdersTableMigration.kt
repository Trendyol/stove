package com.trendyol.stove.examples.kotlin.spring.e2e.setup

import com.trendyol.stove.database.migrations.DatabaseMigration
import com.trendyol.stove.postgres.PostgresSqlMigrationContext
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class CreateOrdersTableMigration : DatabaseMigration<PostgresSqlMigrationContext> {
  override val order: Int = 1

  override suspend fun execute(connection: PostgresSqlMigrationContext) {
    logger.info { "Creating orders table" }
    connection.operations.execute(
      """
      DROP TABLE IF EXISTS orders;
      CREATE TABLE IF NOT EXISTS orders (
        id VARCHAR(255) PRIMARY KEY,
        user_id VARCHAR(255) NOT NULL,
        product_id VARCHAR(255) NOT NULL,
        amount DECIMAL(10, 2) NOT NULL,
        status VARCHAR(50) NOT NULL,
        payment_transaction_id VARCHAR(255),
        created_at TIMESTAMP NOT NULL DEFAULT NOW()
      );
      
      DROP TABLE IF EXISTS user_order_statistics;
      CREATE TABLE IF NOT EXISTS user_order_statistics (
        user_id VARCHAR(255) PRIMARY KEY,
        total_orders INT NOT NULL DEFAULT 0,
        total_amount DECIMAL(10, 2) NOT NULL DEFAULT 0,
        last_order_at TIMESTAMP
      );
      """.trimIndent()
    )
    logger.info { "Orders and user_order_statistics tables created" }
  }
}
