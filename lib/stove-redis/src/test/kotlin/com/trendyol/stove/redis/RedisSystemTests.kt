package com.trendyol.stove.redis

import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.redis.RedisSystem.Companion.client
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.system.stove
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.future.await
import org.slf4j.*
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

// ============================================================================
// Shared components
// ============================================================================

class NoOpApplication : ApplicationUnderTest<Unit> {
  override suspend fun start(configurations: List<String>) = Unit

  override suspend fun stop() = Unit
}

// ============================================================================
// Strategy interface
// ============================================================================

sealed interface RedisTestStrategy {
  val logger: Logger

  suspend fun start()

  suspend fun stop()

  companion object {
    fun select(): RedisTestStrategy {
      val useProvided = System.getenv("USE_PROVIDED")?.toBoolean()
        ?: System.getProperty("useProvided")?.toBoolean()
        ?: false

      return if (useProvided) ProvidedRedisStrategy() else ContainerRedisStrategy()
    }
  }
}

// ============================================================================
// Container-based strategy (default)
// ============================================================================

class ContainerRedisStrategy : RedisTestStrategy {
  override val logger: Logger = LoggerFactory.getLogger(javaClass)

  override suspend fun start() {
    logger.info("Starting Redis tests with container mode")

    val options = RedisOptions(
      container = RedisContainerOptions(),
      configureExposedConfiguration = { _ -> listOf() }
    )

    Stove()
      .with {
        redis { options }
        applicationUnderTest(NoOpApplication())
      }.run()
  }

  override suspend fun stop() {
    com.trendyol.stove.system.Stove
      .stop()
    logger.info("Redis container tests completed")
  }
}

// ============================================================================
// Provided instance strategy
// ============================================================================

class ProvidedRedisStrategy : RedisTestStrategy {
  override val logger: Logger = LoggerFactory.getLogger(javaClass)

  private lateinit var externalContainer: GenericContainer<*>

  override suspend fun start() {
    logger.info("Starting Redis tests with provided mode")

    // Start an external container to simulate a provided instance
    externalContainer = GenericContainer(DockerImageName.parse("redis:7-alpine"))
      .withExposedPorts(6379)
      .withCommand("redis-server", "--requirepass", "password")
      .apply { start() }

    logger.info("External Redis container started at ${externalContainer.host}:${externalContainer.firstMappedPort}")

    val options = RedisOptions
      .provided(
        host = externalContainer.host,
        port = externalContainer.firstMappedPort,
        password = "password",
        runMigrations = true,
        cleanup = { client ->
          logger.info("Running cleanup on provided instance")
          // Clean up test data if needed
        },
        configureExposedConfiguration = { _ -> listOf() }
      )

    Stove()
      .with {
        redis { options }
        applicationUnderTest(NoOpApplication())
      }.run()
  }

  override suspend fun stop() {
    com.trendyol.stove.system.Stove
      .stop()
    externalContainer.stop()
    logger.info("Redis provided tests completed")
  }
}

// ============================================================================
// Kotest project config - selects strategy based on environment
// ============================================================================

class StoveConfig : AbstractProjectConfig() {
  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  private val strategy = RedisTestStrategy.select()

  override suspend fun beforeProject() = strategy.start()

  override suspend fun afterProject() = strategy.stop()
}

// ============================================================================
// Tests
// ============================================================================

class RedisSystemTests :
  ShouldSpec({

    should("work") {
      stove {
        redis {
          client()
            .connect()
            .async()
            .ping()
            .await() shouldBe "PONG"
        }
      }
    }
  })
