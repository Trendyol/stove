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
 * Spring Boot 2.x test setup.
 * Uses [com.trendyol.stove.spring.stoveSpringRegistrar] with `bean<T>()` DSL.
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

  override suspend fun afterProject(): Unit = Stove.stop()
}

/** Concrete test class for Spring Boot 2.x - inherits all tests from fixtures */
class Boot2xBridgeSystemTests : BridgeSystemTests()
