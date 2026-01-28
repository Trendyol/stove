package com.trendyol.stove.example.java.spring.e2e.setup

import com.trendyol.stove.database.migrations.DatabaseMigration
import com.trendyol.stove.postgres.PostgresSqlMigrationContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class CreateProductsTableMigration : DatabaseMigration<PostgresSqlMigrationContext> {
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
        price DOUBLE PRECISION NOT NULL,
        category_id INTEGER NOT NULL,
        created_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        version BIGINT NOT NULL DEFAULT 0
      );
      """.trimIndent()
    )
    logger.info("Products table created")
  }
}
