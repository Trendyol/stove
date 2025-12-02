package com.trendyol.stove.testing.e2e.grpc

import com.squareup.wire.GrpcClient
import com.trendyol.stove.testing.e2e.system.abstractions.SystemOptions
import io.grpc.*
import okhttp3.*
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Configuration options for the gRPC client system.
 *
 * ## Basic Configuration
 *
 * ```kotlin
 * TestSystem()
 *     .with {
 *         grpc {
 *             GrpcSystemOptions(
 *                 host = "localhost",
 *                 port = 50051
 *             )
 *         }
 *     }
 * ```
 *
 * ## With Authentication
 *
 * ```kotlin
 * grpc {
 *     GrpcSystemOptions(
 *         host = "localhost",
 *         port = 50051,
 *         metadata = mapOf("authorization" to "Bearer $token"),
 *         interceptors = listOf(LoggingInterceptor())
 *     )
 * }
 * ```
 *
 * ## Custom Channel
 *
 * For advanced scenarios (custom TLS, load balancing, etc.):
 *
 * ```kotlin
 * grpc {
 *     GrpcSystemOptions(
 *         host = "localhost",
 *         port = 50051,
 *         createChannel = { host, port ->
 *             ManagedChannelBuilder.forAddress(host, port)
 *                 .usePlaintext()
 *                 .enableRetry()
 *                 .build()
 *         }
 *     )
 * }
 * ```
 *
 * @property host The gRPC server host.
 * @property port The gRPC server port.
 * @property usePlaintext Whether to use plaintext (no TLS). Default is true for testing.
 * @property timeout Request timeout duration (default: 30 seconds).
 * @property interceptors List of client interceptors for logging, auth, tracing, etc.
 * @property metadata Default metadata (headers) to send with every request.
 * @property createChannel Factory function for creating the underlying ManagedChannel.
 * @property createWireGrpcClient Factory function for creating Wire's GrpcClient.
 */
@GrpcDsl
data class GrpcSystemOptions(
  val host: String,
  val port: Int,
  val usePlaintext: Boolean = true,
  val timeout: Duration = 30.seconds,
  val interceptors: List<ClientInterceptor> = emptyList(),
  val metadata: Map<String, String> = emptyMap(),
  val createChannel: (host: String, port: Int) -> ManagedChannel = { h, p ->
    defaultChannelBuilder(h, p, usePlaintext, timeout, interceptors, metadata)
  },
  val createWireGrpcClient: (host: String, port: Int) -> GrpcClient = { h, p ->
    defaultWireGrpcClient(h, p, timeout)
  }
) : SystemOptions

/**
 * Creates a default ManagedChannel with standard configuration.
 */
internal fun defaultChannelBuilder(
  host: String,
  port: Int,
  usePlaintext: Boolean,
  timeout: Duration,
  interceptors: List<ClientInterceptor>,
  metadata: Map<String, String>
): ManagedChannel {
  val builder = ManagedChannelBuilder
    .forAddress(host, port)
    .keepAliveTime(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    .keepAliveTimeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)

  if (usePlaintext) {
    builder.usePlaintext()
  }

  // Add metadata interceptor if metadata is provided
  if (metadata.isNotEmpty()) {
    builder.intercept(MetadataInterceptor(metadata))
  }

  // Add user-provided interceptors
  if (interceptors.isNotEmpty()) {
    builder.intercept(interceptors)
  }

  return builder.build()
}

/**
 * Creates a default Wire GrpcClient with standard configuration.
 */
internal fun defaultWireGrpcClient(
  host: String,
  port: Int,
  timeout: Duration
): GrpcClient {
  val okHttpClient = OkHttpClient
    .Builder()
    .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
    .callTimeout(timeout.toJavaDuration())
    .readTimeout(timeout.toJavaDuration())
    .writeTimeout(timeout.toJavaDuration())
    .connectTimeout(timeout.toJavaDuration())
    .build()

  return GrpcClient
    .Builder()
    .client(okHttpClient)
    .baseUrl("http://$host:$port")
    .build()
}

/**
 * Interceptor that adds metadata (headers) to every gRPC call.
 */
@PublishedApi
internal class MetadataInterceptor(
  private val headers: Map<String, String>
) : ClientInterceptor {
  override fun <ReqT, RespT> interceptCall(
    method: MethodDescriptor<ReqT, RespT>,
    callOptions: CallOptions,
    next: Channel
  ): ClientCall<ReqT, RespT> = object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
    next.newCall(method, callOptions)
  ) {
    override fun start(responseListener: Listener<RespT>, metadata: Metadata) {
      headers.forEach { (key, value) ->
        metadata.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value)
      }
      super.start(responseListener, metadata)
    }
  }
}
