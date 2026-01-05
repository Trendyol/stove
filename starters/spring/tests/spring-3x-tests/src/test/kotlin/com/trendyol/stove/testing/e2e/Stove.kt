package com.trendyol.stove.testing.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.reporting.StoveKotestExtension
import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.system.TestSystem
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
open class TestSpringBootApp

/**
 * Spring Boot 3.x test setup.
 * Uses [stoveSpringRegistrar] with `bean<T>()` DSL.
 */
class Stove : AbstractProjectConfig() {
  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  override suspend fun beforeProject(): Unit =
    TestSystem()
      .with {
        bridge()
        springBoot(
          runner = { params ->
            runApplication<TestSpringBootApp>(args = params) {
              addInitializers(
                stoveSpringRegistrar {
                  bean<ParameterCollectorOfSpringBoot>()
                  bean<TestAppInitializers>()
                  bean<ObjectMapper> { StoveSerde.jackson.default }
                  bean { SystemTimeGetUtcNow() }
                }
              )
            }
          },
          withParameters = listOf("context=SetupOfBridgeSystemTests")
        )
      }.run()

  override suspend fun afterProject(): Unit = TestSystem.stop()
}

/** Concrete test class for Spring Boot 3.x - inherits all tests from fixtures */
class Boot3xBridgeSystemTests : BridgeSystemTests()
