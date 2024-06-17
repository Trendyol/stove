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
) : PluggedSystem, RunAware, ExposesConfiguration {
  private lateinit var client: RedisClient
  private lateinit var exposedConfiguration: RedisExposedConfiguration
  private val logger: Logger = LoggerFactory.getLogger(javaClass)
  private val state: StateStorage<RedisExposedConfiguration> =
    testSystem.options.createStateStorage<RedisExposedConfiguration, RedisSystem>()

  override suspend fun run() {
    exposedConfiguration =
      state.capture {
        context.container.start()
        RedisExposedConfiguration(
          context.container.host,
          context.container.firstMappedPort,
          context.container.redisURI,
          context.options.database.toString(),
          context.options.password
        )
      }
    client = RedisClient.create(
      RedisURI.create(
        exposedConfiguration.host,
        exposedConfiguration.port
      ).apply {
        setCredentialsProvider {
          Mono.just(
            RedisCredentials.just(null, exposedConfiguration.password)
          )
        }
      }
    )
  }

  /**
   * Pauses the container. Use with care, as it will pause the container which might affect other tests.
   * @return KafkaSystem
   */
  fun pause(): RedisSystem = context.container.pause().let { this }

  /**
   * Unpauses the container. Use with care, as it will unpause the container which might affect other tests.
   * @return KafkaSystem
   */
  fun unpause(): RedisSystem = context.container.unpause().let { this }

  override suspend fun stop(): Unit = context.container.stop()

  override fun close(): Unit = runBlocking {
    Try {
      if (::client.isInitialized) client.shutdown()
      executeWithReuseCheck { stop() }
    }.recover { logger.warn("Redis client shutdown failed", it) }
  }

  override fun configuration(): List<String> = context.options.configureExposedConfiguration(exposedConfiguration)

  private fun isInitialized(): Boolean = ::client.isInitialized

  companion object {
    fun RedisSystem.client(): RedisClient {
      if (!isInitialized()) throw SystemNotInitializedException(RedisSystem::class)
      return client
    }
  }
}
