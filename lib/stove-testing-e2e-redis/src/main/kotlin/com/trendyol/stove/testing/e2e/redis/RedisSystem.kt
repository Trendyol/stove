@file:Suppress("unused")

package com.trendyol.stove.testing.e2e.redis

import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.reporting.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import io.lettuce.core.*
import kotlinx.coroutines.runBlocking
import org.slf4j.*
import reactor.core.publisher.Mono

/**
 * Redis cache/data store system for testing caching operations.
 *
 * Provides access to a Lettuce Redis client for testing Redis operations.
 * Use [client] to access the underlying [RedisClient] for all Redis operations.
 *
 * ## Accessing Redis Client
 *
 * All Redis operations are performed through the Lettuce client:
 *
 * ```kotlin
 * redis {
 *     val conn = client().connect().sync()
 *
 *     // Set a simple string value
 *     conn.set("user:123", "John Doe")
 *
 *     // Set with expiration (TTL in seconds)
 *     conn.setex("session:abc", 3600, sessionData)
 *
 *     // Get a value
 *     val value = conn.get("user:123")
 *     value shouldBe "John Doe"
 *
 *     // Check key existence
 *     val exists = conn.exists("user:123")
 *     exists shouldBe 1L
 *
 *     // Delete a key
 *     conn.del("user:123")
 *
 *     // Get TTL
 *     val ttl = conn.ttl("session:abc")
 *     ttl shouldBeGreaterThan 0
 * }
 * ```
 *
 * ## Fault Injection Testing
 *
 * Test application behavior during cache outages:
 *
 * ```kotlin
 * redis {
 *     pause()  // Simulate Redis outage
 * }
 *
 * // Test application graceful degradation
 * http {
 *     get<UserResponse>("/users/123") { user ->
 *         // Should still work (from database fallback)
 *         user.name shouldBe "John"
 *     }
 * }
 *
 * redis {
 *     unpause()  // Restore Redis
 * }
 * ```
 *
 * ## Test Workflow Example
 *
 * ```kotlin
 * test("should cache user after first request") {
 *     TestSystem.validate {
 *         // Ensure cache is empty
 *         redis {
 *             val conn = client().connect().sync()
 *             conn.get("user:cache:123") shouldBe null
 *         }
 *
 *         // First request - cache miss, loads from DB
 *         http {
 *             get<UserResponse>("/users/123") { user ->
 *                 user.name shouldBe "John"
 *             }
 *         }
 *
 *         // Verify user is now cached
 *         redis {
 *             val conn = client().connect().sync()
 *             val cached = conn.get("user:cache:123")
 *             cached shouldNotBe null
 *         }
 *     }
 * }
 * ```
 *
 * ## Configuration
 *
 * ```kotlin
 * TestSystem()
 *     .with {
 *         redis {
 *             RedisSystemOptions(
 *                 database = 0,
 *                 configureExposedConfiguration = { cfg ->
 *                     listOf(
 *                         "spring.redis.host=${cfg.host}",
 *                         "spring.redis.port=${cfg.port}"
 *                     )
 *                 }
 *             )
 *         }
 *     }
 * ```
 *
 * @property testSystem The parent test system.
 * @see RedisSystemOptions
 * @see RedisExposedConfiguration
 */
@StoveDsl
class RedisSystem(
  override val testSystem: TestSystem,
  private val context: RedisContext
) : PluggedSystem,
  RunAware,
  ExposesConfiguration,
  Reports {
  private lateinit var client: RedisClient

  private lateinit var exposedConfiguration: RedisExposedConfiguration
  private val logger: Logger = LoggerFactory.getLogger(javaClass)
  private val state: StateStorage<RedisExposedConfiguration> =
    testSystem.options.createStateStorage<RedisExposedConfiguration, RedisSystem>()

  override suspend fun run() {
    exposedConfiguration = obtainExposedConfiguration()
    client = createClient(exposedConfiguration)
    runMigrationsIfNeeded()
  }

  override suspend fun stop(): Unit = whenContainer { it.stop() }

  override fun close(): Unit = runBlocking {
    Try {
      if (::client.isInitialized) {
        context.options.cleanup(client)
        client.shutdown()
      }
      executeWithReuseCheck { stop() }
    }.recover { logger.warn("Redis client shutdown failed", it) }
  }

  override fun configuration(): List<String> = context.options.configureExposedConfiguration(exposedConfiguration)

  /**
   * Pauses the container. Use with care, as it will pause the container which might affect other tests.
   * This operation is not supported when using a provided instance.
   * @return RedisSystem
   */
  fun pause(): RedisSystem {
    recordAction(
      action = "Pause container",
      metadata = mapOf("operation" to "fault-injection")
    )
    return withContainerOrWarn("pause") { it.pause() }
  }

  /**
   * Unpauses the container. Use with care, as it will unpause the container which might affect other tests.
   * This operation is not supported when using a provided instance.
   * @return RedisSystem
   */
  fun unpause(): RedisSystem {
    recordAction(
      action = "Unpause container"
    )
    return withContainerOrWarn("unpause") { it.unpause() }
  }

  private suspend fun obtainExposedConfiguration(): RedisExposedConfiguration =
    when {
      context.options is ProvidedRedisOptions -> context.options.config
      context.runtime is StoveRedisContainer -> startRedisContainer(context.runtime)
      else -> throw UnsupportedOperationException("Unsupported runtime type: ${context.runtime::class}")
    }

  private suspend fun startRedisContainer(container: StoveRedisContainer): RedisExposedConfiguration =
    state.capture {
      container.start()
      RedisExposedConfiguration(
        host = container.host,
        port = container.firstMappedPort,
        redisUri = container.redisURI,
        database = context.options.database.toString(),
        password = context.options.password
      )
    }

  private fun createClient(config: RedisExposedConfiguration): RedisClient =
    RedisClient.create(
      RedisURI.create(config.host, config.port).apply {
        setCredentialsProvider { Mono.just(RedisCredentials.just(null, config.password)) }
      }
    )

  private inline fun withContainerOrWarn(
    operation: String,
    action: (StoveRedisContainer) -> Unit
  ): RedisSystem = when (val runtime = context.runtime) {
    is StoveRedisContainer -> {
      action(runtime)
      this
    }

    is ProvidedRuntime -> {
      logger.warn("$operation() is not supported when using a provided instance")
      this
    }

    else -> {
      throw UnsupportedOperationException("Unsupported runtime type: ${runtime::class}")
    }
  }

  private inline fun whenContainer(action: (StoveRedisContainer) -> Unit) {
    if (context.runtime is StoveRedisContainer) {
      action(context.runtime)
    }
  }

  private suspend fun runMigrationsIfNeeded() {
    if (shouldRunMigrations()) {
      client.connect().use { connection ->
        context.options.migrationCollection.run(RedisMigrationContext(connection, context.options))
      }
    }
  }

  private fun shouldRunMigrations(): Boolean = when {
    context.options is ProvidedRedisOptions -> context.options.runMigrations
    context.runtime is StoveRedisContainer -> !state.isSubsequentRun() || testSystem.options.runMigrationsAlways
    else -> throw UnsupportedOperationException("Unsupported runtime type: ${context.runtime::class}")
  }

  private fun isInitialized(): Boolean = ::client.isInitialized

  companion object {
    fun RedisSystem.client(): RedisClient {
      if (!isInitialized()) throw SystemNotInitializedException(RedisSystem::class)
      return client
    }
  }
}
