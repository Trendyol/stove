package com.trendyol.stove.testing.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import org.springframework.boot.*
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.*
import org.springframework.context.support.GenericApplicationContext
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

class TestAppInitializers : BaseApplicationContextInitializer({
  bean<ObjectMapper> { StoveObjectMapper.Default }
  bean { SystemTimeGetUtcNow() }
}) {
  var onEvent: Boolean = false
  var appReady: Boolean = false

  init {
    register {
      bean<ParameterCollectorOfSpringBoot>()
      bean<TestAppInitializers> { this@TestAppInitializers }
    }
  }

  override fun onEvent(event: ApplicationEvent) {
    super.onEvent(event)
    onEvent = true
  }

  override fun applicationReady(applicationContext: GenericApplicationContext) {
    super.applicationReady(applicationContext)
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

class Setup : AbstractProjectConfig() {
  override suspend fun beforeProject(): Unit =
    TestSystem()
      .with {
        bridge()
        springBoot(
          runner = { params ->
            TestAppRunner.run(params) {
              addInitializers(
                TestAppInitializers()
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

class BridgeSystemTests : ShouldSpec({
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
