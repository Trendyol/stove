package com.trendyol.stove.redis

import com.trendyol.stove.redis.RedisSystem.Companion.client
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.ProvidedRuntime
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RedisShutdownTest : FunSpec({
  test("cleanup failure still shuts down the managed Redis client") {
    Stove().use { stove ->
      var cleanupCalled = false
      val system = RedisSystem(
        stove,
        RedisContext(
          ProvidedRuntime,
          RedisOptions.provided(
            host = "localhost",
            port = 6379,
            password = "",
            runMigrations = false,
            cleanup = {
              cleanupCalled = true
              error("cleanup failed")
            },
            configureExposedConfiguration = { emptyList() }
          )
        )
      )
      try {
        system.run()
        // Redis exposes the native client as its managed API; no connection is opened here.
        val executor = system.client().resources.eventExecutorGroup()
        system.close()
        cleanupCalled shouldBe true
        executor.isTerminated shouldBe true
      } finally {
        system.close()
      }
    }
  }
})
