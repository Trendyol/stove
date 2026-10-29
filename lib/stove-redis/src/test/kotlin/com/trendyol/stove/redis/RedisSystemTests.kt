package com.trendyol.stove.redis

import com.trendyol.stove.chaos.duringFault
import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.redis.RedisSystem.Companion.client
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.system.stove
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.lettuce.core.ClientOptions
import io.lettuce.core.CredentialsProvider
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisCredentials
import io.lettuce.core.RedisException
import io.lettuce.core.RedisURI
import io.lettuce.core.SocketOptions
import kotlinx.coroutines.future.await
import org.slf4j.*
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration

// ============================================================================
// Shared components
// ============================================================================

internal lateinit var applicationRedisConfiguration: RedisExposedConfiguration

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
      configureExposedConfiguration = { configuration ->
        applicationRedisConfiguration = configuration
        emptyList()
      }
    )

    Stove()
      .with {
        redis {
          enableChaos()
          options
        }
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
        configureExposedConfiguration = { configuration ->
          applicationRedisConfiguration = configuration
          emptyList()
        }
      )

    Stove()
      .with {
        redis {
          enableChaos()
          options
        }
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
    should("fault application Redis traffic while direct assertions remain available and recover") {
      withApplicationRedis { application ->
        stove {
          redis {
            application.connect().use { connection ->
              connection.sync().ping() shouldBe "PONG"
              duringFault {
                shouldThrow<RedisException> { connection.sync().ping() }
                shouldThrow<RedisException> { application.connect().use { it.sync().ping() } }
                redis {
                  client().connect().use { it.sync().ping() } shouldBe "PONG"
                }
              }
            }
            application.connect().use { it.sync().ping() } shouldBe "PONG"
          }
        }
      }
    }

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

// Native client models the application's exposed route; direct checks use the Stove Redis DSL.
private suspend fun withApplicationRedis(block: suspend (RedisClient) -> Unit) {
  val configuration = applicationRedisConfiguration
  val uri = RedisURI.create(configuration.redisUri).apply {
    timeout = Duration.ofSeconds(2)
    database = configuration.database.toInt()
    setCredentialsProvider(CredentialsProvider.from { RedisCredentials.just(null, configuration.password) })
  }
  val application = RedisClient.create(uri).apply {
    options = ClientOptions.builder()
      .autoReconnect(false)
      .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(2)).build())
      .build()
  }
  try {
    block(application)
  } finally {
    application.shutdown()
  }
}
