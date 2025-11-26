package com.trendyol.stove.testing.e2e.system

import com.trendyol.stove.testing.e2e.system.abstractions.PluggedSystem
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl

/**
 * The DSL wrapper for writing test validations against registered [PluggedSystem]s.
 *
 * This class provides the entry point for all test assertions and validations.
 * It wraps the [TestSystem] and exposes extension functions for each registered system
 * (HTTP, Kafka, Couchbase, PostgreSQL, etc.).
 *
 * ## Usage
 *
 * Use [TestSystem.validate] to access the validation DSL:
 *
 * ```kotlin
 * TestSystem.validate {
 *     // HTTP assertions
 *     http {
 *         get<UserResponse>("/users/123") { user ->
 *             user.name shouldBe "John"
 *         }
 *     }
 *
 *     // Kafka assertions
 *     kafka {
 *         shouldBePublished<UserCreatedEvent> {
 *             actual.userId == "123"
 *         }
 *     }
 *
 *     // Database assertions using Bridge
 *     using<UserRepository> {
 *         findById("123").name shouldBe "John"
 *     }
 *
 *     // Couchbase assertions
 *     couchbase {
 *         shouldGet<User>("users", "user-123") { user ->
 *             user.name shouldBe "John"
 *         }
 *     }
 * }
 * ```
 *
 * ## Available System DSLs
 *
 * Each registered system provides its own DSL extension:
 * - `http { }` - HTTP client assertions
 * - `kafka { }` - Kafka publish/consume assertions
 * - `couchbase { }` - Couchbase document assertions
 * - `postgresql { }` / `mssql { }` - Database assertions
 * - `elasticsearch { }` - Elasticsearch document assertions
 * - `mongodb { }` - MongoDB document assertions
 * - `wiremock { }` - WireMock stub setup
 * - `using<T> { }` - Bridge to application's DI container
 *
 * @property testSystem The underlying test system containing all registered systems.
 * @see TestSystem.validate
 * @see PluggedSystem
 */
@JvmInline
@StoveDsl
value class ValidationDsl(
  val testSystem: TestSystem
)
