package com.trendyol.stove.wiremock

import arrow.core.getOrElse
import com.github.tomakehurst.wiremock.common.ConsoleNotifier
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.trendyol.stove.serialization.StoveSerde
import com.trendyol.stove.system.*
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl

data class WireMockSystemOptions(
  /**
   * Port of wiremock server
   */
  val port: Int = 9090,
  /**
   * Configures wiremock server
   */
  val configure: WireMockConfiguration.() -> WireMockConfiguration = { this.notifier(ConsoleNotifier(true)) },
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
  val serde: StoveSerde<Any, ByteArray> = StoveSerde.jackson.anyByteArraySerde()
) : SystemOptions

data class WireMockContext(
  val port: Int,
  val removeStubAfterRequestMatched: Boolean,
  val afterStubRemoved: AfterStubRemoved,
  val afterRequest: AfterRequestHandler,
  val serde: StoveSerde<Any, ByteArray>,
  val configure: WireMockConfiguration.() -> WireMockConfiguration
)

internal fun Stove.withWireMock(options: WireMockSystemOptions = WireMockSystemOptions()): Stove =
  WireMockSystem(
    stove = this,
    WireMockContext(
      options.port,
      options.removeStubAfterRequestMatched,
      options.afterStubRemoved,
      options.afterRequest,
      options.serde,
      options.configure
    )
  ).also { getOrRegister(it) }
    .let { this }

internal fun Stove.wiremock(): WireMockSystem =
  getOrNone<WireMockSystem>().getOrElse {
    throw SystemNotRegisteredException(WireMockSystem::class)
  }

@StoveDsl
fun WithDsl.wiremock(
  configure: @StoveDsl () -> WireMockSystemOptions
): Stove = this.stove.withWireMock(configure())

@StoveDsl
suspend fun ValidationDsl.wiremock(validation: @WiremockDsl suspend WireMockSystem.() -> Unit): Unit =
  validation(this.stove.wiremock())
