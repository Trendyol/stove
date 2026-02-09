package com.stove.micronaut.example.e2e

import com.trendyol.stove.postgres.PostgresSqlMigrationContext
import com.trendyol.stove.postgres.PostgresqlMigration
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class CreateProductsTableMigration : PostgresqlMigration {
  private val logger: Logger = LoggerFactory.getLogger(CreateProductsTableMigration::class.java)

  override val order: Int = 1

  override suspend fun execute(connection: PostgresSqlMigrationContext) {
    logger.info("Creating products table")
    connection.operations.execute(
      """
      DROP TABLE IF EXISTS products;
      CREATE TABLE IF NOT EXISTS products (
        id VARCHAR(255) PRIMARY KEY,
        name VARCHAR(255) NOT NULL,
        supplier_id BIGINT NOT NULL,
        is_blacklist BOOLEAN NOT NULL DEFAULT false,
        created_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
      );
      """.trimIndent()
    )
    logger.info("Products table created")
  }
}
