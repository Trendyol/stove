package com.trendyol.stove.testing.e2e.redis

import com.trendyol.stove.testing.e2e.redis.RedisSystem.Companion.client
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.future.await

class Stove : AbstractProjectConfig() {
  override suspend fun beforeProject(): Unit =
    TestSystem()
      .with {
        redis {
          RedisOptions(
            container = RedisContainerOptions(),
            configureExposedConfiguration = { _ -> listOf() }
          )
        }
        applicationUnderTest(NoOpApplication())
      }.run()

  override suspend fun afterProject(): Unit = TestSystem.stop()
}

class NoOpApplication : ApplicationUnderTest<Unit> {
  override suspend fun start(configurations: List<String>) = Unit

  override suspend fun stop() = Unit
}

class RedisSystemTests :
  ShouldSpec({

    should("work") {
      TestSystem.validate {
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
