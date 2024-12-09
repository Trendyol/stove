package com.trendyol.stove.testing

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.*
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.*
import io.micronaut.runtime.EmbeddedApplication
import io.micronaut.runtime.Micronaut
import jakarta.inject.Singleton
import kotlinx.coroutines.delay
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class BridgeSystemTests(
  private val exampleService: ExampleService,
  private val getUtcNow: GetUtcNow,
  private val parameterCollector: ApplicationParameterCollector,
  private val embeddedApplication: EmbeddedApplication<*>
) : FunSpec({

    test("bridge to application") {
      embeddedApplication.isRunning shouldBe true

      exampleService.whatIsTheTime() shouldBe GetUtcNow.frozenTime
      getUtcNow() shouldBe GetUtcNow.frozenTime

      parameterCollector.parameters shouldBe listOf(
        "--test-system=true",
        "--context=SetupOfBridgeSystemTests"
      )

      delay(5.seconds)
    }

    test("resolve multiple") {
      getUtcNow() shouldBe GetUtcNow.frozenTime
      exampleService.whatIsTheTime() shouldBe GetUtcNow.frozenTime

      parameterCollector.parameters shouldBe listOf(
        "--test-system=true",
        "--context=SetupOfBridgeSystemTests"
      )
    }
  })

@Factory
class TestAppConfig {
  @Singleton
  fun objectMapper(): ObjectMapper = ObjectMapper()

  @Singleton
  fun getUtcNow(): GetUtcNow = SystemTimeGetUtcNow()

  @Singleton
  fun exampleService(getUtcNow: GetUtcNow): ExampleService = ExampleService(getUtcNow)

  @Singleton
  fun applicationParameterCollector(environment: io.micronaut.context.env.Environment): ApplicationParameterCollector = ApplicationParameterCollector(environment)
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

@Singleton
class ExampleService(
  private val getUtcNow: GetUtcNow
) {
  fun whatIsTheTime(): Instant = getUtcNow()
}

@Singleton
class ApplicationParameterCollector(
  private val environment: io.micronaut.context.env.Environment
) {
  val parameters: List<String>
    get() = environment.activeNames.toList()
}

class Setup : AbstractProjectConfig() {
  override suspend fun beforeProject() {
    Micronaut
      .build()
      .args("--test-system=true", "--context=SetupOfBridgeSystemTests")
      .start()
  }

  override suspend fun afterProject() {
    // Uygulama durdurma işlemi yapılabilir
  }
}
