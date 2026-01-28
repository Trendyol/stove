package stove.spring.standalone.example.infrastructure.postgres

import io.r2dbc.spi.ConnectionFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.r2dbc.core.DatabaseClient

@Configuration
class PostgresConfiguration {
  @Bean
  fun r2dbcEntityTemplate(connectionFactory: ConnectionFactory): R2dbcEntityTemplate =
    R2dbcEntityTemplate(connectionFactory)

  @Bean
  fun databaseClient(connectionFactory: ConnectionFactory): DatabaseClient =
    DatabaseClient.create(connectionFactory)
}
