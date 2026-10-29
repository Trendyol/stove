package com.trendyol.stove.redis

import com.trendyol.stove.chaos.duringFault
import com.trendyol.stove.chaos.stall
import com.trendyol.stove.redis.RedisSystem.Companion.client
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.ValidationDsl
import com.trendyol.stove.system.abstractions.SystemKey
import com.trendyol.stove.system.closeAll
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.lettuce.core.ClientOptions
import io.lettuce.core.CredentialsProvider
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisCredentials
import io.lettuce.core.RedisException
import io.lettuce.core.RedisURI
import io.lettuce.core.SocketOptions
import kotlinx.coroutines.future.await
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private object Primary : SystemKey
private object Secondary : SystemKey

class RedisChaosUseCasesTest : FunSpec({
  val routes = RedisChaosRoutes()
  beforeSpec { routes.start() }
  afterSpec { routes.close() }

  test("repeated primary outages leave the secondary application route usable") {
    val secondary = routes.connect(Secondary).sync()
    val counter = "secondary-${UUID.randomUUID()}"
    secondary.ping() shouldBe "PONG"

    routes.validation.redis(Primary) {
      repeat(3) { cycle ->
        val primary = routes.connect(Primary).sync()
        primary.ping() shouldBe "PONG"
        duringFault {
          shouldThrow<RedisException> { primary.ping() }
          shouldThrow<RedisException> { routes.freshPing(Primary) }
          secondary.incr(counter) shouldBe cycle + 1L
          redis(Primary) {
            client().connect().use { it.sync().ping() } shouldBe "PONG"
          }
        }
        routes.freshPing(Primary) shouldBe "PONG"
      }
    }
    secondary.get(counter) shouldBe "3"
  }

  test("a stalled write completes once after healing without replaying the fault callback") {
    val application = routes.connect(Primary)
    val counter = "stalled-${UUID.randomUUID()}"
    var callbacks = 0
    application.sync().ping() shouldBe "PONG"

    routes.validation.redis(Primary) {
      lateinit var pending: io.lettuce.core.RedisFuture<Long>
      duringFault(stall()) {
        callbacks++
        pending = application.async().incr(counter)
        // This bounded wait neither cancels the original command nor reissues its write.
        shouldThrow<TimeoutException> { pending.get(150, TimeUnit.MILLISECONDS) }
        pending.isDone shouldBe false
        redis(Primary) {
          client().connect().use { it.sync().get(counter) } shouldBe null
        }
      }
      pending.await() shouldBe 1L
      callbacks shouldBe 1
      application.sync().get(counter) shouldBe "1"
      client().connect().use { it.sync().get(counter) } shouldBe "1"
    }
  }
})

/** Owns two independent application routes to one server, including all application connections. */
private class RedisChaosRoutes {
  private val container = GenericContainer(DockerImageName.parse("redis:7-alpine"))
    .withExposedPorts(6379)
    .withCommand("redis-server", "--requirepass", "password")
  private val stove = Stove()
  private val applications = mutableMapOf<SystemKey, RedisClient>()
  val validation = ValidationDsl(stove)

  suspend fun start() {
    container.start()
    stove.with {
      listOf(Primary, Secondary).forEach { key ->
        redis(key) {
          enableChaos()
          RedisOptions.provided(
            host = container.host,
            port = container.firstMappedPort,
            password = "password",
            database = 3,
            configureExposedConfiguration = { configuration ->
              applications[key] = applicationClient(configuration)
              emptyList()
            }
          )
        }
      }
    }
    listOf(Primary, Secondary).forEach { key ->
      stove.redis(key).run()
      stove.redis(key).configuration()
    }
  }

  // These native clients model the AUT; direct assertions use the Stove Redis DSL.
  fun connect(key: SystemKey) = applications.getValue(key).connect()

  fun freshPing(key: SystemKey): String = connect(key).use { it.sync().ping() }

  suspend fun close() {
    closeAll(
      { closeAll(applications.values) { it.shutdown() } },
      { stove.close() },
      { container.stop() }
    )
  }

  private fun applicationClient(configuration: RedisExposedConfiguration): RedisClient {
    val uri = RedisURI.create(configuration.redisUri).apply {
      timeout = Duration.ofSeconds(5)
      database = configuration.database.toInt()
      setCredentialsProvider(CredentialsProvider.from { RedisCredentials.just(null, configuration.password) })
    }
    return RedisClient.create(uri).apply {
      options = ClientOptions.builder()
        .autoReconnect(false)
        .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(2)).build())
        .build()
    }
  }
}
