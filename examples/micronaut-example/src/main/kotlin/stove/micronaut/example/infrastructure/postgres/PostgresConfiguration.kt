package stove.micronaut.example.infrastructure.postgres

import io.micronaut.context.annotation.Factory
import io.r2dbc.spi.ConnectionFactory
import jakarta.inject.Singleton

@Factory
class PostgresConfiguration {
  @Singleton
  fun connectionFactory(connectionFactory: ConnectionFactory): ConnectionFactory = connectionFactory
}
