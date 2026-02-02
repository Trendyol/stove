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
      )
      """.trimIndent()
    )
    logger.info { "Orders table created" }
  }
}
