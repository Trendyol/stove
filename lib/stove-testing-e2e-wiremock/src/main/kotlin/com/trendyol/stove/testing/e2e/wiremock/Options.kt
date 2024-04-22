package com.trendyol.stove.testing.e2e.wiremock

import arrow.core.getOrElse
import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl

data class WireMockSystemOptions(
  /**
   * Port of wiremock server
   */
  val port: Int = 8080,
  /**
   * Removes the stub when request matches/completes
   * Default value is false
   */
  val removeStubAfterRequestMatched: Boolean = false,
  /**
   * Called after stub removed
   */
  val afterStubRemoved: AfterStubRemoved = { _, _ -> },
  /**
   * Called after request handled
   */
  val afterRequest: AfterRequestHandler = { _, _ -> },
  /**
   * ObjectMapper for serialization/deserialization
   */
  val objectMapper: ObjectMapper = StoveObjectMapper.Default
) : SystemOptions

data class WireMockContext(
  val port: Int,
  val removeStubAfterRequestMatched: Boolean,
  val afterStubRemoved: AfterStubRemoved,
  val afterRequest: AfterRequestHandler,
  val objectMapper: ObjectMapper
)

internal fun TestSystem.withWireMock(options: WireMockSystemOptions = WireMockSystemOptions()): TestSystem =
  WireMockSystem(
    testSystem = this,
    WireMockContext(
      options.port,
      options.removeStubAfterRequestMatched,
      options.afterStubRemoved,
      options.afterRequest,
      options.objectMapper
    )
  ).also { getOrRegister(it) }
    .let { this }

internal fun TestSystem.wiremock(): WireMockSystem =
  getOrNone<WireMockSystem>().getOrElse {
    throw SystemNotRegisteredException(WireMockSystem::class)
  }

@StoveDsl
fun WithDsl.wiremock(configure: @StoveDsl () -> WireMockSystemOptions): TestSystem = this.testSystem.withWireMock(configure())

@StoveDsl
suspend fun ValidationDsl.wiremock(validation: @WiremockDsl suspend WireMockSystem.() -> Unit): Unit =
  validation(this.testSystem.wiremock())
