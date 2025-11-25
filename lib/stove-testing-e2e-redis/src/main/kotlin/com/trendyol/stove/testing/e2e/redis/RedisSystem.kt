@file:Suppress("unused")

package com.trendyol.stove.testing.e2e.redis

import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import io.lettuce.core.*
import kotlinx.coroutines.runBlocking
import org.slf4j.*
import reactor.core.publisher.Mono

@StoveDsl
class RedisSystem(
  override val testSystem: TestSystem,
  private val context: RedisContext
) : PluggedSystem,
  RunAware,
  ExposesConfiguration {
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
  fun pause(): RedisSystem = withContainerOrWarn("pause") { it.pause() }

  /**
   * Unpauses the container. Use with care, as it will unpause the container which might affect other tests.
   * This operation is not supported when using a provided instance.
   * @return RedisSystem
   */
  fun unpause(): RedisSystem = withContainerOrWarn("unpause") { it.unpause() }

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
