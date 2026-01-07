package com.trendyol.stove

import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.serialization.StoveSerde
import com.trendyol.stove.spring.*
import com.trendyol.stove.system.Stove
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
open class TestSpringBootApp

/**
 * Spring Boot 4.x test setup.
 * Uses [com.trendyol.stove.spring.stoveSpring4xRegistrar] with `registerBean<T>()` DSL.
 */
class StoveConfig : AbstractProjectConfig() {
  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  override suspend fun beforeProject(): Unit =
    Stove()
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

  override suspend fun afterProject(): Unit = Stove.stop()
}

/** Concrete test class for Spring Boot 4.x - inherits all tests from fixtures */
class Boot4xBridgeSystemTests : BridgeSystemTests()
