package com.trendyol.stove.testing.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.*
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import java.time.Instant

@Factory
class TestAppConfig {
  @Singleton
  fun objectMapper(): ObjectMapper = ObjectMapper()

  @Singleton
  fun getUtcNow(): GetUtcNow = SystemTimeGetUtcNow()

  @Singleton
  fun exampleService(getUtcNow: GetUtcNow): ExampleService = ExampleService(getUtcNow)
}

fun interface GetUtcNow {
  companion object {
    val frozenTime: Instant = Instant.parse("2021-01-01T00:00:00Z")
  }

  operator fun invoke(): Instant
}

class SystemTimeGetUtcNow : GetUtcNow {
  override fun invoke(): Instant = GetUtcNow.frozenTime
}

class ExampleService(
  private val getUtcNow: GetUtcNow
) {
  fun whatIsTheTime(): Instant = getUtcNow()
}

object TestAppRunner {
  fun run(
    args: Array<String>,
    init: ApplicationContext.() -> Unit = {}
  ): ApplicationContext {
    val context = ApplicationContext
      .builder()
      .args(*args)
      .packages(TestAppConfig::class.java.packageName)
      .build()
      .also(init)
      .start()

    return context
  }
}

class Stove : AbstractProjectConfig() {
  override suspend fun beforeProject() = TestSystem()
    .with {
      bridge()
      micronaut(
        runner = { params ->
          TestAppRunner.run(params)
        }
      )
    }.run()

  override suspend fun afterProject() = TestSystem.stop()
}

class BridgeSystemTests :
  FunSpec({
    test("bridge to application") {
      validate {
        using<ExampleService> {
          whatIsTheTime() shouldBe GetUtcNow.frozenTime
        }

        using<GetUtcNow> {
          invoke() shouldBe GetUtcNow.frozenTime
        }
      }
    }

    test("resolve multiple") {
      validate {
        using<GetUtcNow, ExampleService> { getUtcNow, exampleService ->
          getUtcNow() shouldBe GetUtcNow.frozenTime
          exampleService.whatIsTheTime() shouldBe GetUtcNow.frozenTime
        }
      }
    }
  })
