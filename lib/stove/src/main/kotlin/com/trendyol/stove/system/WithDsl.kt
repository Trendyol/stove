package com.trendyol.stove.system

import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl

/**
 * The DSL wrapper for constructing and configuring the test system with [PluggedSystem]s.
 *
 * This class provides the entry point for registering all components that your tests need:
 * databases, message brokers, HTTP clients, mock servers, and the application under test.
 *
 * ## Usage
 *
 * Use [Stove.with] to access the configuration DSL:
 *
 * ```kotlin
 * Stove()
 *     .with {
 *         // Configure HTTP client
 *         httpClient {
 *             HttpClientSystemOptions(baseUrl = "http://localhost:8080")
 *         }
 *
 *         // Configure Kafka
 *         kafka {
 *             KafkaSystemOptions {
 *                 listOf("kafka.bootstrapServers=${it.bootstrapServers}")
 *             }
 *         }
 *
 *         // Configure PostgreSQL
 *         postgresql {
 *             PostgresqlOptions(configureExposedConfiguration = { cfg ->
 *                 listOf(
 *                     "spring.datasource.url=${cfg.jdbcUrl}",
 *                     "spring.datasource.username=${cfg.username}",
 *                     "spring.datasource.password=${cfg.password}"
 *                 )
 *             })
 *         }
 *
 *         // Configure WireMock for external service mocking
 *         wiremock {
 *             WireMockSystemOptions(port = 9090)
 *         }
 *
 *         // Enable Bridge for DI container access
 *         bridge()
 *
 *         // Configure the application under test
 *         springBoot(
 *             runner = { params -> myApp.run(params) },
 *             withParameters = listOf("server.port=8080")
 *         )
 *     }
 *     .run()
 * ```
 *
 * ## Available System Configurations
 *
 * Each system provides its own configuration function:
 * - `httpClient { }` - HTTP client for API testing
 * - `kafka { }` - Apache Kafka for messaging
 * - `couchbase { }` - Couchbase database
 * - `postgresql { }` - PostgreSQL database
 * - `mssql { }` - Microsoft SQL Server database
 * - `mongodb { }` - MongoDB database
 * - `elasticsearch { }` - Elasticsearch search engine
 * - `redis { }` - Redis cache
 * - `wiremock { }` - WireMock for HTTP mocking
 * - `bridge()` - Bridge to application's DI container
 * - `springBoot { }` / `ktor { }` - Application under test
 *
 * @property stove The underlying Stove instance being configured.
 * @see Stove.with
 * @see PluggedSystem
 */
@JvmInline
@StoveDsl
value class WithDsl(
  val stove: Stove
) {
  /**
   * Registers the application under test with Stove.
   *
   * This is typically called by framework-specific functions like `springBoot()` or `ktor()`.
   * You generally don't need to call this directly.
   *
   * @param applicationUnderTest The application to be tested.
   */
  @StoveDsl
  fun applicationUnderTest(applicationUnderTest: ApplicationUnderTest<*>) {
    this.stove.applicationUnderTest(applicationUnderTest)
  }
}
