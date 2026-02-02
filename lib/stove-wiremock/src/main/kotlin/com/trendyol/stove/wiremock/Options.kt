package com.trendyol.stove.wiremock

import arrow.core.getOrElse
import com.github.tomakehurst.wiremock.common.ConsoleNotifier
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.trendyol.stove.serialization.StoveSerde
import com.trendyol.stove.system.*
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl

/**
 * Configuration exposed by WireMock after it starts.
 *
 * This allows the application under test to receive the actual WireMock URL,
 * which is especially useful when using dynamic ports (port = 0).
 *
 * @property host The host where WireMock is running.
 * @property port The actual port WireMock is listening on.
 * @property baseUrl The complete base URL (http://host:port).
 */
data class WireMockExposedConfiguration(
  val host: String,
  val port: Int
) : ExposedConfiguration {
  val baseUrl: String get() = "http://$host:$port"
}

data class WireMockSystemOptions(
  /**
   * Port of wiremock server.
   * Defaults to 0, which lets WireMock pick an available port automatically.
   * This avoids port conflicts, especially in CI environments.
   */
  val port: Int = 0,
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
  val serde: StoveSerde<Any, ByteArray> = StoveSerde.jackson.anyByteArraySerde(),
  /**
   * Configures the exposed configuration for the application under test.
   * Use this to inject WireMock's URL into your application's configuration.
   *
   * Example:
   * ```kotlin
   * WireMockSystemOptions(
   *     port = 0, // dynamic port
   *     configureExposedConfiguration = { cfg ->
   *         listOf(
   *             "external-apis.inventory.url=${cfg.baseUrl}",
   *             "external-apis.payment.url=${cfg.baseUrl}"
   *         )
   *     }
   * )
   * ```
   */
  override val configureExposedConfiguration: (WireMockExposedConfiguration) -> List<String> = { _ -> listOf() }
) : SystemOptions,
  ConfiguresExposedConfiguration<WireMockExposedConfiguration>

data class WireMockContext(
  val port: Int,
  val removeStubAfterRequestMatched: Boolean,
  val afterStubRemoved: AfterStubRemoved,
  val afterRequest: AfterRequestHandler,
  val serde: StoveSerde<Any, ByteArray>,
  val configure: WireMockConfiguration.() -> WireMockConfiguration,
  val configureExposedConfiguration: (WireMockExposedConfiguration) -> List<String>
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
      options.configure,
      options.configureExposedConfiguration
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
