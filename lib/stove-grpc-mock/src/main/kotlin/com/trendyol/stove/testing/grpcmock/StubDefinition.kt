package com.trendyol.stove.testing.grpcmock

import com.google.protobuf.Message
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

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
  companion object {
    /**
     * Typed request matcher: parses the request bytes as [T] and evaluates the predicate.
     * Bytes that do not parse as [T] never match.
     *
     * ```kotlin
     * mockUnary(
     *   serviceName = "users.UserService",
     *   methodName = "GetUser",
     *   requestMatcher = RequestMatcher.message<GetUserRequest> { it.userId == "123" },
     *   response = response
     * )
     * ```
     */
    inline fun <reified T : Message> message(crossinline predicate: (T) -> Boolean): Custom {
      val parser = ProtoPayloads.parserFor(T::class.java)
      return Custom { bytes -> parser(bytes)?.let(predicate) == true }
    }
  }

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

  internal val methodType: MethodDescriptor.MethodType
    get() = when (this) {
      is Unary, is Error -> MethodDescriptor.MethodType.UNARY
      is ServerStream -> MethodDescriptor.MethodType.SERVER_STREAMING
      is ClientStream -> MethodDescriptor.MethodType.CLIENT_STREAMING
      is BidiStream -> MethodDescriptor.MethodType.BIDI_STREAMING
    }

  /**
   * Unary RPC: single request -> single response
   *
   * @property delay Optional artificial latency before the response is sent — makes
   *   client deadline (`DEADLINE_EXCEEDED`) testing possible.
   */
  data class Unary(
    override val requestMatcher: RequestMatcher = RequestMatcher.Any,
    override val metadataMatcher: MetadataMatcher = MetadataMatcher.Any,
    val response: Message,
    val delay: Duration? = null
  ) : StubDefinition()

  /**
   * Server streaming RPC: single request -> stream of responses
   *
   * @property delay Optional artificial latency before the stream starts.
   * @property thenFailWith When set, the stream emits all [responses] and then fails
   *   with this status instead of completing — the classic mid-stream failure scenario.
   */
  data class ServerStream(
    override val requestMatcher: RequestMatcher = RequestMatcher.Any,
    override val metadataMatcher: MetadataMatcher = MetadataMatcher.Any,
    val responses: List<Message>,
    val delay: Duration? = null,
    val thenFailWith: Status? = null
  ) : StubDefinition()

  /**
   * Client streaming RPC: stream of requests -> single response
   *
   * @property delay Optional artificial latency before the response is sent.
   */
  data class ClientStream(
    override val requestMatcher: RequestMatcher = RequestMatcher.Any,
    override val metadataMatcher: MetadataMatcher = MetadataMatcher.Any,
    val response: Message,
    val delay: Duration? = null
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
   *
   * @property trailers Optional error trailers, e.g. carrying `google.rpc.Status` details
   *   the way real gRPC APIs return structured errors.
   * @property delay Optional artificial latency before the error is sent.
   */
  data class Error(
    override val requestMatcher: RequestMatcher = RequestMatcher.Any,
    override val metadataMatcher: MetadataMatcher = MetadataMatcher.Any,
    val status: Status,
    val message: String? = null,
    val trailers: Metadata? = null,
    val delay: Duration? = null
  ) : StubDefinition()
}

/**
 * A stub together with the test that registered it. Requests matched by this stub
 * are attributed to that test; a null [testId] means the stub was registered outside
 * any test context and matches every test's view.
 */
internal data class RegisteredStub(
  val key: StubKey,
  val definition: StubDefinition,
  val testId: String?
)

/**
 * Record of a request that was received by the mock server.
 *
 * @property testId The test this request belongs to, when provable — from the matched
 *   stub's registration or from `X-Stove-Test-Id`/baggage metadata. Null means untagged:
 *   the request is visible to every test (fail-open scoping).
 */
data class ReceivedRequest(
  val stubKey: StubKey,
  val requestBytes: ByteArray,
  val metadata: Metadata = Metadata(),
  val timestamp: Long = System.currentTimeMillis(),
  val matched: Boolean,
  val stubId: String? = null,
  val testId: String? = null
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

internal fun RequestMatcher.matches(requestBytes: ByteArray): Boolean = when (this) {
  is RequestMatcher.Any -> true
  is RequestMatcher.ExactBytes -> requestBytes.contentEquals(bytes)
  is RequestMatcher.ExactMessage -> requestBytes.contentEquals(message.toByteArray())
  is RequestMatcher.Custom -> matcher(requestBytes)
}

internal fun MetadataMatcher.matches(metadata: Metadata): Boolean = when (this) {
  is MetadataMatcher.Any -> true

  is MetadataMatcher.HasHeader -> {
    val headerKey = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)
    metadata.get(headerKey) == value
  }

  is MetadataMatcher.BearerToken -> {
    val auth = metadata.get(GrpcMetadataKeys.AUTHORIZATION)
    auth == "Bearer $token"
  }

  is MetadataMatcher.RequiresAuth -> {
    val auth = metadata.get(GrpcMetadataKeys.AUTHORIZATION)
    !auth.isNullOrBlank()
  }

  is MetadataMatcher.Custom -> matcher(metadata)

  is MetadataMatcher.All -> matchers.all { it.matches(metadata) }
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
