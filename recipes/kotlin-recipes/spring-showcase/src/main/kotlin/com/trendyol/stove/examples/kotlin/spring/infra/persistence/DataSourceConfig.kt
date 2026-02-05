package com.trendyol.stove.examples.kotlin.spring.infra.persistence

import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

/**
 * Configuration for JDBC DataSource.
 * Required for db-scheduler which needs JDBC, while the app uses R2DBC for reactive operations.
 * Derives JDBC URL from R2DBC URL for consistency.
 */
@Configuration
class DataSourceConfig {
  @Bean
  fun dataSource(
    @Value("\${spring.r2dbc.url}") r2dbcUrl: String,
    @Value("\${spring.r2dbc.username}") username: String,
    @Value("\${spring.r2dbc.password}") password: String
  ): DataSource = HikariDataSource().apply {
    // Convert R2DBC URL to JDBC URL
    jdbcUrl = r2dbcUrl.replace("r2dbc:", "jdbc:")
    this.username = username
    this.password = password
    driverClassName = "org.postgresql.Driver"
    maximumPoolSize = 5
    poolName = "db-scheduler-pool"
    validate()
  }
}
