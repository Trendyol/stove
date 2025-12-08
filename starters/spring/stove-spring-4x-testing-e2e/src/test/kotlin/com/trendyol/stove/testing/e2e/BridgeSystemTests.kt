package com.trendyol.stove.testing.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import org.springframework.boot.*
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

object TestAppRunner {
  fun run(
    args: Array<String>,
    init: SpringApplication.() -> Unit = {}
  ): ConfigurableApplicationContext = runApplication<TestSpringBootApp>(args = args) {
    init()
  }
}

@SpringBootApplication
open class TestSpringBootApp

fun interface GetUtcNow {
  companion object {
    val frozenTime: Instant = Instant.parse("2021-01-01T00:00:00Z")
  }

  operator fun invoke(): Instant
}

class SystemTimeGetUtcNow : GetUtcNow {
  override fun invoke(): Instant = GetUtcNow.frozenTime
}

class TestAppInitializers {
  var onEvent: Boolean = false
  var appReady: Boolean = false

  @EventListener(ApplicationReadyEvent::class)
  fun applicationReady() {
    onEvent = true
    appReady = true
  }
}

@Component
class ExampleService(
  private val getUtcNow: GetUtcNow
) {
  fun whatIsTheTime(): Instant = getUtcNow()
}

class ParameterCollectorOfSpringBoot(
  private val applicationArguments: ApplicationArguments
) {
  val parameters: List<String>
    get() = applicationArguments.sourceArgs.toList()
}

class Stove : AbstractProjectConfig() {
  override suspend fun beforeProject(): Unit =
    TestSystem()
      .with {
        bridge()
        springBoot(
          runner = { params ->
            TestAppRunner.run(params) {
              addInitializers(
                stoveSpringRegistrar {
                  registerBean<ParameterCollectorOfSpringBoot>()
                  registerBean<TestAppInitializers>()
                  registerBean<ObjectMapper> { StoveSerde.jackson.default }
                  registerBean { SystemTimeGetUtcNow() }
                }
              )
            }
          },
          withParameters = listOf(
            "context=SetupOfBridgeSystemTests"
          )
        )
      }.run()

  override suspend fun afterProject(): Unit = TestSystem.stop()
}

class BridgeSystemTests :
  ShouldSpec({
    should("bridge to application") {
      validate {
        using<ExampleService> {
          whatIsTheTime() shouldBe GetUtcNow.frozenTime
        }

        using<ParameterCollectorOfSpringBoot> {
          parameters shouldBe listOf(
            "--test-system=true",
            "--context=SetupOfBridgeSystemTests"
          )
        }

        delay(5.seconds)
        using<TestAppInitializers> {
          appReady shouldBe true
          onEvent shouldBe true
        }
      }
    }

    should("resolve multiple") {
      validate {
        using<GetUtcNow, TestAppInitializers> { getUtcNow: GetUtcNow, testAppInitializers: TestAppInitializers ->
          getUtcNow() shouldBe GetUtcNow.frozenTime
          testAppInitializers.appReady shouldBe true
          testAppInitializers.onEvent shouldBe true
        }

        using<GetUtcNow, TestAppInitializers, ParameterCollectorOfSpringBoot> { getUtcNow, testAppInitializers, parameterCollectorOfSpringBoot ->
          getUtcNow() shouldBe GetUtcNow.frozenTime
          testAppInitializers.appReady shouldBe true
          testAppInitializers.onEvent shouldBe true
          parameterCollectorOfSpringBoot.parameters shouldBe listOf(
            "--test-system=true",
            "--context=SetupOfBridgeSystemTests"
          )
        }

        using<GetUtcNow, TestAppInitializers, ParameterCollectorOfSpringBoot, ExampleService> { getUtcNow, testAppInitializers, parameterCollectorOfSpringBoot, exampleService ->
          getUtcNow() shouldBe GetUtcNow.frozenTime
          testAppInitializers.appReady shouldBe true
          testAppInitializers.onEvent shouldBe true
          parameterCollectorOfSpringBoot.parameters shouldBe listOf(
            "--test-system=true",
            "--context=SetupOfBridgeSystemTests"
          )
          exampleService.whatIsTheTime() shouldBe GetUtcNow.frozenTime
        }

        using<GetUtcNow, TestAppInitializers, ParameterCollectorOfSpringBoot, ExampleService, ObjectMapper> { getUtcNow, testAppInitializers, parameterCollectorOfSpringBoot, exampleService, objectMapper ->
          getUtcNow() shouldBe GetUtcNow.frozenTime
          testAppInitializers.appReady shouldBe true
          testAppInitializers.onEvent shouldBe true
          parameterCollectorOfSpringBoot.parameters shouldBe listOf(
            "--test-system=true",
            "--context=SetupOfBridgeSystemTests"
          )
          exampleService.whatIsTheTime() shouldBe GetUtcNow.frozenTime
          objectMapper.writeValueAsString(mapOf("a" to "b")) shouldBe """{"a":"b"}"""
        }
      }
    }
  })
