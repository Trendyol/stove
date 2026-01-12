package com.trendyol.stove.testing.grpcmock

import com.google.protobuf.Message
import io.grpc.Metadata
import io.grpc.Status
import kotlinx.coroutines.flow.Flow

/**
 * Key for identifying a stub in the registry.
 */
data class StubKey(
  val serviceName: String,
  val methodName: String,
  val id: String = java.util.UUID
    .randomUUID()
    .toString()
) {
  val fullMethodName: String = "$serviceName/$methodName"
}

/**
 * Matcher for incoming requests.
 */
sealed class RequestMatcher {
  /** Matches any request */
  data object Any : RequestMatcher()

  /** Matches requests with exact message content */
  data class ExactMessage(
    val message: Message
  ) : RequestMatcher()

  /** Matches requests with exact byte content */
  data class ExactBytes(
    val bytes: ByteArray
  ) : RequestMatcher() {
    override fun equals(other: kotlin.Any?): Boolean {
      if (this === other) return true
      if (other !is ExactBytes) return false
      return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()
  }

  /** Custom matcher function */
  data class Custom(
    val matcher: (ByteArray) -> Boolean
  ) : RequestMatcher()
}

/**
 * Matcher for request metadata (headers).
 */
sealed class MetadataMatcher {
  /** Matches any metadata (no restrictions) */
  data object Any : MetadataMatcher()

  /** Requires specific header to be present with exact value */
  data class HasHeader(
    val key: String,
    val value: String
  ) : MetadataMatcher()

  /** Requires Authorization header with specific Bearer token */
  data class BearerToken(
    val token: String
  ) : MetadataMatcher()

  /** Requires any valid Authorization header (non-empty) */
  data object RequiresAuth : MetadataMatcher()

  /** Custom metadata matcher */
  data class Custom(
    val matcher: (Metadata) -> Boolean
  ) : MetadataMatcher()

  /** Combines multiple matchers (all must match) */
  data class All(
    val matchers: List<MetadataMatcher>
  ) : MetadataMatcher() {
    constructor(vararg matchers: MetadataMatcher) : this(matchers.toList())
  }
}

/**
 * Definition of a stub response.
 */
sealed class StubDefinition {
  abstract val requestMatcher: RequestMatcher
  abstract val metadataMatcher: MetadataMatcher

  /**
   * Unary RPC: single request -> single response
   */
  data class Unary(
    override val requestMatcher: RequestMatcher = RequestMatcher.Any,
    override val metadataMatcher: MetadataMatcher = MetadataMatcher.Any,
    val response: Message
  ) : StubDefinition()

  /**
   * Server streaming RPC: single request -> stream of responses
   */
  data class ServerStream(
    override val requestMatcher: RequestMatcher = RequestMatcher.Any,
    override val metadataMatcher: MetadataMatcher = MetadataMatcher.Any,
    val responses: List<Message>
  ) : StubDefinition()

  /**
   * Client streaming RPC: stream of requests -> single response
   */
  data class ClientStream(
    override val requestMatcher: RequestMatcher = RequestMatcher.Any,
    override val metadataMatcher: MetadataMatcher = MetadataMatcher.Any,
    val response: Message
  ) : StubDefinition()

  /**
   * Bidirectional streaming RPC: stream of requests <-> stream of responses
   * The handler receives a flow of request bytes and returns a flow of response messages.
   */
  data class BidiStream(
    override val requestMatcher: RequestMatcher = RequestMatcher.Any,
    override val metadataMatcher: MetadataMatcher = MetadataMatcher.Any,
    val handler: suspend (Flow<ByteArray>) -> Flow<Message>
  ) : StubDefinition()

  /**
   * Error response for any RPC type
   */
  data class Error(
    override val requestMatcher: RequestMatcher = RequestMatcher.Any,
    override val metadataMatcher: MetadataMatcher = MetadataMatcher.Any,
    val status: Status,
    val message: String? = null
  ) : StubDefinition()
}

/**
 * Record of a request that was received by the mock server.
 */
data class ReceivedRequest(
  val stubKey: StubKey,
  val requestBytes: ByteArray,
  val metadata: Metadata = Metadata(),
  val timestamp: Long = System.currentTimeMillis(),
  val matched: Boolean,
  val stubId: String? = null
) {
  /** Get authorization header value if present */
  val authorizationHeader: String?
    get() = metadata.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER))

  /** Get bearer token if present (strips "Bearer " prefix) */
  val bearerToken: String?
    get() = authorizationHeader?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is ReceivedRequest) return false
    return stubKey == other.stubKey &&
      requestBytes.contentEquals(other.requestBytes) &&
      timestamp == other.timestamp &&
      matched == other.matched
  }

  override fun hashCode(): Int {
    var result = stubKey.hashCode()
    result = 31 * result + requestBytes.contentHashCode()
    result = 31 * result + timestamp.hashCode()
    result = 31 * result + matched.hashCode()
    return result
  }
}

/**
 * Common metadata keys for gRPC.
 */
object GrpcMetadataKeys {
  val AUTHORIZATION: Metadata.Key<String> = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
  val CONTENT_TYPE: Metadata.Key<String> = Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER)

  /** Create a custom ASCII metadata key */
  fun ascii(name: String): Metadata.Key<String> = Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER)

  /** Create a custom binary metadata key */
  fun binary(name: String): Metadata.Key<ByteArray> = Metadata.Key.of("$name-bin", Metadata.BINARY_BYTE_MARSHALLER)
}
