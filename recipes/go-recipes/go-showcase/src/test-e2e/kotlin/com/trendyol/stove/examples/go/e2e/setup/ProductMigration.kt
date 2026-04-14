package com.trendyol.stove.examples.go.e2e.setup

import com.trendyol.stove.database.migrations.DatabaseMigration
import com.trendyol.stove.postgres.PostgresSqlMigrationContext

class ProductMigration : DatabaseMigration<PostgresSqlMigrationContext> {
  override val order: Int = 1

  override suspend fun execute(connection: PostgresSqlMigrationContext) {
    connection.operations.execute(
      """
      CREATE TABLE IF NOT EXISTS products (
          id VARCHAR(255) PRIMARY KEY,
          name VARCHAR(255) NOT NULL,
          price DECIMAL(10, 2) NOT NULL
      );
      """.trimIndent()
    )
  }
}
