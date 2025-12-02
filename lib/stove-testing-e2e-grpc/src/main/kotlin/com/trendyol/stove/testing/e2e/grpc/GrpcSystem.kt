@file:Suppress("MemberVisibilityCanBePrivate")

package com.trendyol.stove.testing.e2e.grpc

import arrow.core.getOrElse
import com.squareup.wire.GrpcClient
import com.squareup.wire.Service
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.ValidationDsl
import com.trendyol.stove.testing.e2e.system.WithDsl
import com.trendyol.stove.testing.e2e.system.abstractions.PluggedSystem
import com.trendyol.stove.testing.e2e.system.abstractions.SystemNotRegisteredException
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientInterceptors
import io.grpc.ManagedChannel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * gRPC client system for testing gRPC APIs.
 *
 * Provides a fluent DSL for making gRPC requests and asserting responses.
 * Supports multiple gRPC providers through a provider-agnostic design.
 *
 * ## Typed Channel (Recommended)
 *
 * For any stub type with a Channel constructor (grpc-kotlin, Wire, etc.):
 *
 * ```kotlin
 * grpc {
 *     channel<GreeterServiceStub> {
 *         val response = sayHello(HelloRequest(name = "World"))
 *         response.message shouldBe "Hello, World!"
 *     }
 * }
 * ```
 *
 * ## Wire Clients
 *
 * For Wire-generated service clients:
 *
 * ```kotlin
 * grpc {
 *     wireClient<GreeterServiceClient> {
 *         val response = SayHello().execute(HelloRequest(name = "World"))
 *         response.message shouldBe "Hello, World!"
 *     }
 * }
 * ```
 *
 * ## Custom Providers
 *
 * For any other gRPC library:
 *
 * ```kotlin
 * grpc {
 *     withEndpoint({ host, port -> CustomGrpcLib.connect(host, port) }) {
 *         call(...) shouldBe expected
 *     }
 * }
 * ```
 *
 * ## Streaming
 *
 * All streaming types work naturally with Kotlin coroutines:
 *
 * ```kotlin
 * grpc {
 *     channel<StreamServiceStub> {
 *         // Server streaming
 *         serverStream(request).collect { response ->
 *             // assertions on each response
 *         }
 *
 *         // Client streaming
 *         val response = clientStream(flow { emit(request1); emit(request2) })
 *
 *         // Bidirectional streaming
 *         bidiStream(requestFlow).collect { response ->
 *             // assertions
 *         }
 *     }
 * }
 * ```
 *
 * ## Per-Call Metadata
 *
 * Add metadata (headers) to specific calls:
 *
 * ```kotlin
 * grpc {
 *     channel<GreeterServiceStub>(
 *         metadata = mapOf("authorization" to "Bearer $token")
 *     ) {
 *         sayHello(request)
 *     }
 * }
 * ```
 *
 * @property testSystem The parent test system.
 * @property options gRPC client configuration options.
 * @see GrpcSystemOptions
 */
@GrpcDsl
class GrpcSystem(
  override val testSystem: TestSystem,
  @PublishedApi internal val options: GrpcSystemOptions
) : PluggedSystem {
  private val channelInitialized = AtomicBoolean(false)

  @PublishedApi
  internal val grpcChannel: ManagedChannel by lazy {
    channelInitialized.set(true)
    options.createChannel(options.host, options.port)
  }

  @PublishedApi
  internal val wireGrpcClient: GrpcClient by lazy {
    options.createWireGrpcClient(options.host, options.port)
  }

  /**
   * Execute gRPC calls using a Wire-generated client.
   *
   * The client is automatically created from the GrpcClient, and `this` in the block
   * refers to the client instance.
   *
   * ```kotlin
   * grpc {
   *     wireClient<GreeterServiceClient> {
   *         val response = SayHello().execute(HelloRequest(name = "World"))
   *         response.message shouldBe "Hello!"
   *     }
   * }
   * ```
   *
   * @param T The Wire service client type.
   * @param block The block to execute with the client as receiver.
   */
  @GrpcDsl
  inline fun <reified T : Service> wireClient(
    block: @GrpcDsl T.() -> Unit
  ): GrpcSystem {
    val client = wireGrpcClient.create(T::class)
    block(client)
    return this
  }

  /**
   * Execute gRPC calls using a custom client created by the provided factory.
   *
   * This allows integration with any gRPC library by providing a factory function
   * that takes host and port and returns the client.
   *
   * ```kotlin
   * grpc {
   *     withEndpoint({ h, p -> CustomGrpcLib.connect(h, p) }) {
   *         call(...) shouldBe expected
   *     }
   * }
   * ```
   *
   * @param T The custom client type.
   * @param factory Factory function that creates the client from host and port.
   * @param block The block to execute with the client as receiver.
   */
  @GrpcDsl
  inline fun <T> withEndpoint(
    factory: (host: String, port: Int) -> T,
    block: @GrpcDsl T.() -> Unit
  ): GrpcSystem {
    val client = factory(options.host, options.port)
    block(client)
    return this
  }

  /**
   * Execute gRPC calls using any stub type that has a Channel constructor.
   *
   * The stub is automatically created from the channel using reflection.
   * Works with grpc-kotlin stubs, Wire-generated stubs, and any other
   * stub that takes a Channel as constructor parameter.
   *
   * ```kotlin
   * grpc {
   *     channel<GreeterServiceStub> {
   *         val response = sayHello(HelloRequest(name = "World"))
   *         response.message shouldBe "Hello!"
   *     }
   * }
   * ```
   *
   * @param T The stub type with a Channel constructor.
   * @param metadata Optional per-call metadata to add to all requests in this block.
   * @param block The block to execute with the stub as receiver.
   */
  @GrpcDsl
  inline fun <reified T : Any> channel(
    metadata: Map<String, String> = emptyMap(),
    block: @GrpcDsl T.() -> Unit
  ): GrpcSystem {
    val stubInstance = createStubFromChannel<T>(metadata)
    block(stubInstance)
    return this
  }

  /**
   * Execute operations with direct access to the ManagedChannel.
   *
   * Use this for advanced scenarios where you need full control over
   * stub creation, custom interceptors, or channel operations.
   *
   * ```kotlin
   * grpc {
   *     rawChannel { ch ->
   *         val interceptedChannel = ClientInterceptors.intercept(ch, myInterceptor)
   *         val stub = GreeterGrpc.newBlockingStub(interceptedChannel)
   *         // ... use stub
   *     }
   * }
   * ```
   *
   * @param block The block to execute with the channel.
   */
  @GrpcDsl
  inline fun rawChannel(
    block: @GrpcDsl (ManagedChannel) -> Unit
  ): GrpcSystem {
    block(grpcChannel)
    return this
  }

  /**
   * Execute operations with direct access to the Wire GrpcClient.
   *
   * Use this for advanced Wire scenarios where you need direct client access.
   *
   * ```kotlin
   * grpc {
   *     rawWireClient { client ->
   *         val service = client.create(MyServiceClient::class)
   *         // ... use service
   *     }
   * }
   * ```
   *
   * @param block The block to execute with the Wire GrpcClient.
   */
  @GrpcDsl
  inline fun rawWireClient(
    block: @GrpcDsl (GrpcClient) -> Unit
  ): GrpcSystem {
    block(wireGrpcClient)
    return this
  }

  /**
   * Exposes the [ManagedChannel] used by this system.
   */
  fun managedChannel(): ManagedChannel = grpcChannel

  /**
   * Exposes the Wire [GrpcClient] used by this system.
   */
  fun grpcClient(): GrpcClient = wireGrpcClient

  @GrpcDsl
  override fun then(): TestSystem = testSystem

  override fun close() {
    if (channelInitialized.get()) {
      grpcChannel.shutdown()
    }
  }

  /**
   * Returns a channel with optional metadata interceptor applied.
   */
  @PublishedApi
  internal fun channelWithMetadata(metadata: Map<String, String>): Channel =
    if (metadata.isNotEmpty()) {
      ClientInterceptors.intercept(grpcChannel, MetadataInterceptor(metadata))
    } else {
      grpcChannel
    }

  /**
   * Creates a stub instance from a Channel using Java reflection.
   *
   * This method handles internal constructors (like Wire-generated stubs) by
   * using setAccessible(true). It looks for constructors that take:
   * - Just a Channel
   * - Channel and CallOptions
   */
  @PublishedApi
  internal inline fun <reified T : Any> createStubFromChannel(
    metadata: Map<String, String>
  ): T {
    val stubClass = T::class.java
    val channelToUse = channelWithMetadata(metadata)

    // Find a constructor that takes Channel (or Channel + CallOptions)
    val constructor = stubClass.declaredConstructors
      .filter { ctor ->
        val params = ctor.parameterTypes
        params.isNotEmpty() && Channel::class.java.isAssignableFrom(params[0])
      }
      .minByOrNull { it.parameterCount }
      ?: throw IllegalArgumentException(
        "Cannot find suitable constructor for stub ${stubClass.simpleName}. " +
          "Expected a constructor with Channel parameter."
      )

    constructor.isAccessible = true

    @Suppress("UNCHECKED_CAST")
    return when (constructor.parameterCount) {
      1 -> constructor.newInstance(channelToUse) as T
      2 -> constructor.newInstance(channelToUse, CallOptions.DEFAULT) as T
      else -> throw IllegalArgumentException(
        "Unexpected constructor signature for stub ${stubClass.simpleName}"
      )
    }
  }
}

internal fun TestSystem.withGrpc(options: GrpcSystemOptions): TestSystem {
  this.getOrRegister(GrpcSystem(this, options))
  return this
}

internal fun TestSystem.grpc(): GrpcSystem = getOrNone<GrpcSystem>().getOrElse {
  throw SystemNotRegisteredException(GrpcSystem::class)
}

/**
 * Registers the gRPC client system with the test system.
 *
 * ```kotlin
 * TestSystem()
 *     .with {
 *         grpc {
 *             GrpcSystemOptions(host = "localhost", port = 50051)
 *         }
 *     }
 * ```
 *
 * @param configure Configuration block returning [GrpcSystemOptions].
 * @return The test system for fluent chaining.
 */
@StoveDsl
fun WithDsl.grpc(configure: @StoveDsl () -> GrpcSystemOptions): TestSystem =
  this.testSystem.withGrpc(configure())

/**
 * Executes gRPC assertions within the validation DSL.
 *
 * ```kotlin
 * TestSystem.validate {
 *     grpc {
 *         channel<GreeterServiceStub> {
 *             sayHello(request).message shouldBe "Hello!"
 *         }
 *     }
 * }
 * ```
 *
 * @param validation The gRPC assertion block.
 */
@StoveDsl
suspend fun ValidationDsl.grpc(
  validation: @GrpcDsl suspend GrpcSystem.() -> Unit
): Unit = validation(this.testSystem.grpc())
