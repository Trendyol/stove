package com.trendyol.stove.examples.kotlin.spring.e2e.setup

import com.trendyol.stove.database.migrations.DatabaseMigration
import com.trendyol.stove.postgres.PostgresSqlMigrationContext
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class OrderExampleInitialMigration : DatabaseMigration<PostgresSqlMigrationContext> {
  override val order: Int = 1

  override suspend fun execute(connection: PostgresSqlMigrationContext) {
    logger.info { "Creating orders table" }
    connection.operations.execute(
      """
    ${orders()}
    ${orderStatistics()}
    ${dbScheduler()}
      """.trimIndent()
    )
    logger.info { "Orders, user_order_statistics, and scheduled_tasks tables created" }
  }

  private fun dbScheduler(): String = """ -- db-scheduler table for scheduled tasks
        -- Schema from: https://github.com/kagkarlsson/db-scheduler/blob/master/db-scheduler/src/test/resources/postgresql_tables.sql
        DROP TABLE IF EXISTS scheduled_tasks;
        CREATE TABLE IF NOT EXISTS scheduled_tasks (
          task_name TEXT NOT NULL,
          task_instance TEXT NOT NULL,
          task_data BYTEA,
          execution_time TIMESTAMP WITH TIME ZONE NOT NULL,
          picked BOOLEAN NOT NULL,
          picked_by TEXT,
          last_success TIMESTAMP WITH TIME ZONE,
          last_failure TIMESTAMP WITH TIME ZONE,
          consecutive_failures INT,
          last_heartbeat TIMESTAMP WITH TIME ZONE,
          version BIGINT NOT NULL,
          priority SMALLINT,
          PRIMARY KEY (task_name, task_instance)
        );
        CREATE INDEX IF NOT EXISTS execution_time_idx ON scheduled_tasks (execution_time);
        CREATE INDEX IF NOT EXISTS last_heartbeat_idx ON scheduled_tasks (last_heartbeat);
        CREATE INDEX IF NOT EXISTS priority_execution_time_idx ON scheduled_tasks (priority DESC, execution_time ASC);"""

  private fun orderStatistics(): String = """  DROP TABLE IF EXISTS user_order_statistics;
        CREATE TABLE IF NOT EXISTS user_order_statistics (
          user_id VARCHAR(255) PRIMARY KEY,
          total_orders INT NOT NULL DEFAULT 0,
          total_amount DECIMAL(10, 2) NOT NULL DEFAULT 0,
          last_order_at TIMESTAMP
        );
        """

  private fun orders(): String = """  DROP TABLE IF EXISTS orders;
        CREATE TABLE IF NOT EXISTS orders (
          id VARCHAR(255) PRIMARY KEY,
          user_id VARCHAR(255) NOT NULL,
          product_id VARCHAR(255) NOT NULL,
          amount DECIMAL(10, 2) NOT NULL,
          status VARCHAR(50) NOT NULL,
          payment_transaction_id VARCHAR(255),
          created_at TIMESTAMP NOT NULL DEFAULT NOW()
        );"""
}
