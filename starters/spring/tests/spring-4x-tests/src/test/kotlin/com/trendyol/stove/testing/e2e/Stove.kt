package com.trendyol.stove.testing.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.system.TestSystem
import io.kotest.core.config.AbstractProjectConfig
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
open class TestSpringBootApp

/**
 * Spring Boot 4.x test setup.
 * Uses [stoveSpring4xRegistrar] with `registerBean<T>()` DSL.
 */
class Stove : AbstractProjectConfig() {
  override suspend fun beforeProject(): Unit =
    TestSystem()
      .with {
        bridge()
        springBoot(
          runner = { params ->
            runApplication<TestSpringBootApp>(args = params) {
              addInitializers(
                stoveSpring4xRegistrar {
                  registerBean<ParameterCollectorOfSpringBoot>()
                  registerBean<TestAppInitializers>()
                  registerBean<ObjectMapper> { StoveSerde.jackson.default }
                  registerBean { SystemTimeGetUtcNow() }
                }
              )
            }
          },
          withParameters = listOf("context=SetupOfBridgeSystemTests")
        )
      }.run()

  override suspend fun afterProject(): Unit = TestSystem.stop()
}

/** Concrete test class for Spring Boot 4.x - inherits all tests from fixtures */
class Boot4xBridgeSystemTests : BridgeSystemTests()
