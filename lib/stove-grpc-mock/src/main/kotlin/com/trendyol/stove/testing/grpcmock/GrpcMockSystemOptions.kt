package com.trendyol.stove.testing.grpcmock

import arrow.core.getOrElse
import com.trendyol.stove.system.*
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl
import io.grpc.ServerBuilder

/**
 * Callback invoked after a stub is matched and used.
 */
typealias AfterStubMatched = (StubKey, StubDefinition) -> Unit

/**
 * Callback invoked for each request received.
 */
typealias OnRequestReceived = (StubKey, ByteArray) -> Unit

/**
 * Configuration exposed by gRPC Mock after it starts.
 *
 * This allows the application under test to receive the actual gRPC mock URL,
 * which is especially useful when using dynamic ports (port = 0).
 *
 * @property host The host where gRPC mock is running.
 * @property port The actual port gRPC mock is listening on.
 */
data class GrpcMockExposedConfiguration(
  val host: String,
  val port: Int
) : ExposedConfiguration

/**
 * Configuration options for the native gRPC mock server.
 *
 * @property port The port to run the mock server on.
 *   Defaults to 0, which lets the system pick an available port automatically.
 *   This avoids port conflicts, especially in CI environments.
 * @property removeStubAfterRequestMatched If true, stubs are removed after being matched once.
 * @property afterStubMatched Callback invoked after a stub is matched.
 * @property onRequestReceived Callback invoked for each request received.
 * @property serverBuilder Optional custom server builder configuration.
 * @property configureExposedConfiguration Callback to expose the gRPC mock configuration to the application.
 */
data class GrpcMockSystemOptions(
  val port: Int = 0,
  val removeStubAfterRequestMatched: Boolean = false,
  val afterStubMatched: AfterStubMatched = { _, _ -> },
  val onRequestReceived: OnRequestReceived = { _, _ -> },
  val serverBuilder: (ServerBuilder<*>) -> ServerBuilder<*> = { it },
  override val configureExposedConfiguration: (GrpcMockExposedConfiguration) -> List<String> = { _ -> listOf() }
) : SystemOptions,
  ConfiguresExposedConfiguration<GrpcMockExposedConfiguration>

/**
 * Internal context for the gRPC mock system.
 */
internal data class GrpcMockContext(
  val port: Int,
  val removeStubAfterRequestMatched: Boolean,
  val afterStubMatched: AfterStubMatched,
  val onRequestReceived: OnRequestReceived,
  val serverBuilder: (ServerBuilder<*>) -> ServerBuilder<*>,
  val configureExposedConfiguration: (GrpcMockExposedConfiguration) -> List<String>
)

internal fun Stove.withGrpcMock(options: GrpcMockSystemOptions): Stove =
  GrpcMockSystem(
    stove = this,
    GrpcMockContext(
      options.port,
      options.removeStubAfterRequestMatched,
      options.afterStubMatched,
      options.onRequestReceived,
      options.serverBuilder,
      options.configureExposedConfiguration
    )
  ).also { getOrRegister(it) }
    .let { this }

internal fun Stove.grpcMock(): GrpcMockSystem = getOrNone<GrpcMockSystem>().getOrElse {
  throw SystemNotRegisteredException(GrpcMockSystem::class)
}

/**
 * Registers the native gRPC mock system with Stove.
 *
 * ```kotlin
 * Stove()
 *   .with {
 *     grpcMock {
 *       GrpcMockSystemOptions(port = 9090)
 *     }
 *   }
 * ```
 */
@StoveDsl
fun WithDsl.grpcMock(configure: @StoveDsl () -> GrpcMockSystemOptions): Stove =
  this.stove.withGrpcMock(configure())

/**
 * Access the gRPC mock system for stub configuration in tests.
 *
 * ```kotlin
 * stove {
 *   grpcMock {
 *     mockUnary(
 *       serviceName = "greeting.GreeterService",
 *       methodName = "SayHello",
 *       response = HelloResponse.newBuilder().setMessage("Hello!").build()
 *     )
 *   }
 * }
 * ```
 */
@StoveDsl
suspend fun ValidationDsl.grpcMock(
  validation: @GrpcMockDsl suspend GrpcMockSystem.() -> Unit
): Stove {
  validation(stove.grpcMock())
  return stove
}
