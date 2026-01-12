@file:Suppress("unused", "MagicNumber")

package com.trendyol.stove.testing.grpcmock

import arrow.core.*
import com.github.benmanes.caffeine.cache.*
import com.google.protobuf.Message
import com.trendyol.stove.functional.*
import com.trendyol.stove.reporting.*
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.*
import io.grpc.*
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Native gRPC mock server for testing gRPC service integrations.
 *
 * This implementation provides full support for all gRPC RPC types:
 * - Unary (request-response)
 * - Server streaming (single request, stream of responses)
 * - Client streaming (stream of requests, single response)
 * - Bidirectional streaming (stream of requests, stream of responses)
 *
 * ## Configuration
 *
 * ```kotlin
 * Stove()
 *   .with {
 *     grpcMock {
 *       GrpcMockSystemOptions(port = 9090)
 *     }
 *     grpc {
 *       GrpcSystemOptions(host = "localhost", port = 9090)
 *     }
 *   }
 * ```
 *
 * ## Mocking Unary Calls
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
 *
 * ## Mocking Authenticated Calls
 *
 * ```kotlin
 * stove {
 *   grpcMock {
 *     // Require specific bearer token
 *     mockUnary(
 *       serviceName = "secure.SecureService",
 *       methodName = "GetSecret",
 *       metadataMatcher = MetadataMatcher.BearerToken("valid-token"),
 *       response = SecretResponse.newBuilder().setData("secret").build()
 *     )
 *
 *     // Or use custom header matching
 *     mockUnary(
 *       serviceName = "secure.SecureService",
 *       methodName = "GetSecret",
 *       metadataMatcher = MetadataMatcher.HasHeader("x-api-key", "my-api-key"),
 *       response = SecretResponse.newBuilder().setData("secret").build()
 *     )
 *   }
 * }
 * ```
 */
@GrpcMockDsl
class GrpcMockSystem internal constructor(
  override val stove: Stove,
  private val ctx: GrpcMockContext
) : PluggedSystem,
  ValidatedSystem,
  RunAware,
  Reports {
  private val logger = LoggerFactory.getLogger(javaClass)
  override val reportSystemName: String = "gRPC Mock"

  private val stubs = ConcurrentHashMap<String, MutableList<Pair<StubKey, StubDefinition>>>()
  private val requestLog: Cache<String, ReceivedRequest> = Caffeine
    .newBuilder()
    .maximumSize(10_000)
    .build()

  private lateinit var server: Server

  // ==================== Lifecycle ====================

  override suspend fun run() {
    server = ctx
      .serverBuilder(ServerBuilder.forPort(ctx.port))
      .intercept(MetadataCapturingInterceptor)
      .fallbackHandlerRegistry(DynamicHandlerRegistry())
      .build()
      .also { it.start() }
    logger.info("gRPC Mock server started on port ${ctx.port}")
  }

  override suspend fun stop() {
    if (::server.isInitialized) {
      server.shutdown().awaitTermination(5, TimeUnit.SECONDS)
      logger.info("gRPC Mock server stopped")
    }
  }

  override fun close(): Unit = runBlocking {
    Try { stop() }.recover { logger.warn("Error stopping gRPC mock: ${it.message}") }
  }

  // ==================== Stub Registration ====================

  /**
   * Mocks a unary RPC (single request → single response).
   *
   * @param serviceName The fully qualified gRPC service name (e.g., "greeting.GreeterService")
   * @param methodName The RPC method name (e.g., "SayHello")
   * @param requestMatcher Optional matcher for filtering requests. Defaults to [RequestMatcher.Any].
   * @param metadataMatcher Optional matcher for filtering by gRPC metadata/headers. Defaults to [MetadataMatcher.Any].
   *   Use [MetadataMatcher.BearerToken] for Bearer token auth, [MetadataMatcher.HasHeader] for custom headers.
   * @param response The protobuf [Message] to return when this stub is matched
   * @return This [GrpcMockSystem] instance for chaining
   *
   * @sample
   * ```kotlin
   * grpcMock {
   *   mockUnary(
   *     serviceName = "greeting.GreeterService",
   *     methodName = "SayHello",
   *     response = HelloResponse.newBuilder().setMessage("Hello!").build()
   *   )
   * }
   * ```
   */
  @GrpcMockDsl
  suspend fun mockUnary(
    serviceName: String,
    methodName: String,
    requestMatcher: RequestMatcher = RequestMatcher.Any,
    metadataMatcher: MetadataMatcher = MetadataMatcher.Any,
    response: Message
  ): GrpcMockSystem = registerStub(
    serviceName,
    methodName,
    StubDefinition.Unary(requestMatcher, metadataMatcher, response),
    "unary"
  ) { response.toString().take(200).some() }

  /**
   * Mocks a server streaming RPC (single request → stream of responses).
   *
   * The mock will send all responses in sequence when a matching request is received.
   *
   * @param serviceName The fully qualified gRPC service name (e.g., "streaming.ItemService")
   * @param methodName The RPC method name (e.g., "ListItems")
   * @param requestMatcher Optional matcher for filtering requests. Defaults to [RequestMatcher.Any].
   * @param metadataMatcher Optional matcher for filtering by gRPC metadata/headers. Defaults to [MetadataMatcher.Any].
   * @param responses The list of protobuf [Message]s to stream back. Must not be empty.
   * @return This [GrpcMockSystem] instance for chaining
   * @throws IllegalArgumentException if [responses] is empty
   *
   * @sample
   * ```kotlin
   * grpcMock {
   *   mockServerStream(
   *     serviceName = "streaming.ItemService",
   *     methodName = "ListItems",
   *     responses = listOf(
   *       Item.newBuilder().setId("1").build(),
   *       Item.newBuilder().setId("2").build()
   *     )
   *   )
   * }
   * ```
   */
  @GrpcMockDsl
  suspend fun mockServerStream(
    serviceName: String,
    methodName: String,
    requestMatcher: RequestMatcher = RequestMatcher.Any,
    metadataMatcher: MetadataMatcher = MetadataMatcher.Any,
    responses: List<Message>
  ): GrpcMockSystem {
    require(responses.isNotEmpty()) { "responses must not be empty" }
    return registerStub(
      serviceName,
      methodName,
      StubDefinition.ServerStream(requestMatcher, metadataMatcher, responses),
      "server stream",
      metadata = mapOf("responseCount" to responses.size)
    )
  }

  /**
   * Mocks a client streaming RPC (stream of requests → single response).
   *
   * The mock will collect all incoming requests and return the configured response
   * when the client completes the stream.
   *
   * **Note:** The [requestMatcher] is evaluated against **only the first request** in the stream,
   * because stub matching happens before the full stream is received. If you need to validate
   * all requests in a client stream, consider using [mockBidiStream] with a custom handler.
   *
   * @param serviceName The fully qualified gRPC service name (e.g., "upload.UploadService")
   * @param methodName The RPC method name (e.g., "UploadChunks")
   * @param requestMatcher Optional matcher for the first request in the stream. Defaults to [RequestMatcher.Any].
   * @param metadataMatcher Optional matcher for filtering by gRPC metadata/headers. Defaults to [MetadataMatcher.Any].
   * @param response The protobuf [Message] to return after the client completes streaming
   * @return This [GrpcMockSystem] instance for chaining
   *
   * @sample
   * ```kotlin
   * grpcMock {
   *   mockClientStream(
   *     serviceName = "upload.UploadService",
   *     methodName = "UploadChunks",
   *     response = UploadResponse.newBuilder().setSuccess(true).build()
   *   )
   * }
   * ```
   */
  @GrpcMockDsl
  suspend fun mockClientStream(
    serviceName: String,
    methodName: String,
    requestMatcher: RequestMatcher = RequestMatcher.Any,
    metadataMatcher: MetadataMatcher = MetadataMatcher.Any,
    response: Message
  ): GrpcMockSystem = registerStub(
    serviceName,
    methodName,
    StubDefinition.ClientStream(requestMatcher, metadataMatcher, response),
    "client stream"
  ) { response.toString().take(200).some() }

  /**
   * Mocks a bidirectional streaming RPC (stream of requests ↔ stream of responses).
   *
   * The [handler] receives a flow of raw request bytes and should return a flow of response messages.
   * This allows full control over the streaming behavior, including transforming requests into responses.
   *
   * @param serviceName The fully qualified gRPC service name (e.g., "chat.ChatService")
   * @param methodName The RPC method name (e.g., "Chat")
   * @param requestMatcher Optional matcher. Currently not used for bidi streams as matching happens dynamically.
   * @param metadataMatcher Optional matcher for filtering by gRPC metadata/headers. Defaults to [MetadataMatcher.Any].
   * @param handler A suspending function that transforms the incoming request flow into a response flow.
   *   The handler receives raw [ByteArray] request bytes which can be parsed using protobuf's `parseFrom`.
   * @return This [GrpcMockSystem] instance for chaining
   *
   * @sample
   * ```kotlin
   * grpcMock {
   *   mockBidiStream(
   *     serviceName = "chat.ChatService",
   *     methodName = "Chat"
   *   ) { requestFlow ->
   *     requestFlow.map { bytes ->
   *       val request = ChatMessage.parseFrom(bytes)
   *       ChatMessage.newBuilder()
   *         .setMessage("Echo: ${request.message}")
   *         .build()
   *     }
   *   }
   * }
   * ```
   */
  @GrpcMockDsl
  suspend fun mockBidiStream(
    serviceName: String,
    methodName: String,
    requestMatcher: RequestMatcher = RequestMatcher.Any,
    metadataMatcher: MetadataMatcher = MetadataMatcher.Any,
    handler: suspend (Flow<ByteArray>) -> Flow<Message>
  ): GrpcMockSystem = registerStub(
    serviceName,
    methodName,
    StubDefinition.BidiStream(requestMatcher, metadataMatcher, handler),
    "bidi stream"
  )

  /**
   * Mocks a gRPC error response for any RPC type.
   *
   * When a matching request is received, the mock will respond with the specified gRPC error status.
   *
   * @param serviceName The fully qualified gRPC service name (e.g., "users.UserService")
   * @param methodName The RPC method name (e.g., "GetUser")
   * @param requestMatcher Optional matcher for filtering requests. Defaults to [RequestMatcher.Any].
   * @param metadataMatcher Optional matcher for filtering by gRPC metadata/headers. Defaults to [MetadataMatcher.Any].
   * @param status The gRPC [Status.Code] to return (e.g., NOT_FOUND, UNAUTHENTICATED, PERMISSION_DENIED)
   * @param message Optional error message description. Defaults to the status code name.
   * @return This [GrpcMockSystem] instance for chaining
   *
   * @sample
   * ```kotlin
   * grpcMock {
   *   mockError(
   *     serviceName = "users.UserService",
   *     methodName = "GetUser",
   *     status = Status.Code.NOT_FOUND,
   *     message = "User not found"
   *   )
   * }
   * ```
   */
  @GrpcMockDsl
  suspend fun mockError(
    serviceName: String,
    methodName: String,
    requestMatcher: RequestMatcher = RequestMatcher.Any,
    metadataMatcher: MetadataMatcher = MetadataMatcher.Any,
    status: Status.Code,
    message: String = status.name
  ): GrpcMockSystem = registerStub(
    serviceName,
    methodName,
    StubDefinition.Error(requestMatcher, metadataMatcher, Status.fromCode(status), message),
    "error",
    metadata = mapOf("status" to status.name, "message" to message)
  )

  // ==================== Validation & Reporting ====================

  @GrpcMockDsl
  override suspend fun validate() {
    val unmatched = requestLog.asMap().values.filter { !it.matched }

    if (unmatched.isNotEmpty()) {
      val error = AssertionError(
        "There are ${unmatched.size} unmatched gRPC requests:\n" +
          unmatched.joinToString("\n") { "  - ${it.stubKey.fullMethodName}" }
      )
      reporter.record(
        ReportEntry.failure(
          system = reportSystemName,
          testId = reporter.currentTestId(),
          action = "Validate: All gRPC requests should match registered stubs",
          error = error.message.orEmpty(),
          expected = "0 unmatched requests".some(),
          actual = "${unmatched.size} unmatched request(s)".some()
        )
      )
      throw error
    }

    reporter.record(
      ReportEntry.success(
        system = reportSystemName,
        testId = reporter.currentTestId(),
        action = "Validate: All gRPC requests matched registered stubs"
      )
    )
  }

  @GrpcMockDsl
  override fun then(): Stove = stove

  override fun snapshot(): SystemSnapshot {
    val allStubs = stubs.values.flatten()
    val allRequests = requestLog.asMap().values

    return SystemSnapshot(
      system = reportSystemName,
      state = mapOf(
        "registeredStubs" to allStubs.map { (key, def) ->
          mapOf("id" to key.id, "service" to key.serviceName, "method" to key.methodName, "type" to def::class.simpleName)
        },
        "receivedRequests" to allRequests.map { req ->
          mapOf(
            "method" to req.stubKey.fullMethodName,
            "matched" to req.matched,
            "timestamp" to req.timestamp,
            "hasAuth" to (req.authorizationHeader != null)
          )
        }
      ),
      summary = """
        |Registered stubs: ${allStubs.size}
        |Received requests: ${allRequests.size}
        |Matched requests: ${allRequests.count { it.matched }}
        |Unmatched requests: ${allRequests.count { !it.matched }}
        |Authenticated requests: ${allRequests.count { it.authorizationHeader != null }}
      """.trimMargin()
    )
  }

  // ==================== Internal: Registration ====================

  private suspend fun registerStub(
    serviceName: String,
    methodName: String,
    stub: StubDefinition,
    stubType: String,
    metadata: Map<String, Any> = emptyMap(),
    outputProvider: () -> Option<String> = { None }
  ): GrpcMockSystem {
    val key = StubKey(serviceName, methodName)
    val authInfo = when (val matcher = stub.metadataMatcher) {
      is MetadataMatcher.BearerToken -> " (authenticated)"
      is MetadataMatcher.RequiresAuth -> " (requires auth)"
      is MetadataMatcher.HasHeader -> " (header: ${matcher.key})"
      else -> ""
    }
    report(
      action = "Register $stubType stub: $serviceName/$methodName$authInfo",
      output = outputProvider(),
      metadata = metadata
    ) {
      stubs.computeIfAbsent(key.fullMethodName) { mutableListOf() }.add(key to stub)
      logger.debug("Registered stub for ${key.fullMethodName} (id: ${key.id})")
    }
    return this
  }

  // ==================== Internal: Stub Lookup ====================

  private fun findAndProcessStub(
    fullMethodName: String,
    requestBytes: ByteArray,
    metadata: Metadata
  ): Option<Pair<StubKey, StubDefinition>> {
    val stubKey = fullMethodName.toStubKey()
    ctx.onRequestReceived(stubKey, requestBytes)

    return stubs[fullMethodName]
      ?.find { (_, stub) ->
        stub.requestMatcher.matches(requestBytes) && stub.metadataMatcher.matches(metadata)
      }?.also { (key, stub) ->
        logRequest(key, requestBytes, metadata, matched = true)
        removeStubIfNeeded(key, fullMethodName)
        ctx.afterStubMatched(key, stub)
      }.toOption()
      .onNone { logRequest(stubKey, requestBytes, metadata, matched = false) }
  }

  private fun removeStubIfNeeded(key: StubKey, fullMethodName: String) {
    if (ctx.removeStubAfterRequestMatched) {
      stubs[fullMethodName]?.removeIf { it.first.id == key.id }
      logger.debug("Removed stub ${key.id} after match")
    }
  }

  private fun logRequest(key: StubKey, requestBytes: ByteArray, metadata: Metadata, matched: Boolean) {
    requestLog.put(
      UUID.randomUUID().toString(),
      ReceivedRequest(key, requestBytes, metadata, matched = matched, stubId = if (matched) key.id else null)
    )
  }

  // ==================== Internal: Handler Registry ====================

  private inner class DynamicHandlerRegistry : HandlerRegistry() {
    override fun lookupMethod(methodName: String, authority: String?): ServerMethodDefinition<*, *>? {
      logger.debug("Looking up method: $methodName")
      return stubs[methodName]?.firstOrNull()?.let { (_, stub) ->
        createHandler(methodName, stub.methodType)
      } ?: run {
        logger.warn("No stub registered for method: $methodName")
        null
      }
    }
  }

  private fun createHandler(
    fullMethodName: String,
    methodType: MethodDescriptor.MethodType
  ): ServerMethodDefinition<ByteArray, ByteArray> {
    val method = MethodDescriptor
      .newBuilder<ByteArray, ByteArray>()
      .setType(methodType)
      .setFullMethodName(fullMethodName)
      .setRequestMarshaller(ByteArrayMarshaller)
      .setResponseMarshaller(ByteArrayMarshaller)
      .build()

    val handler = when (methodType) {
      MethodDescriptor.MethodType.UNARY -> unaryHandler(fullMethodName)
      MethodDescriptor.MethodType.SERVER_STREAMING -> serverStreamHandler(fullMethodName)
      MethodDescriptor.MethodType.CLIENT_STREAMING -> clientStreamHandler(fullMethodName)
      MethodDescriptor.MethodType.BIDI_STREAMING -> bidiStreamHandler(fullMethodName)
      else -> unaryHandler(fullMethodName)
    }

    return ServerMethodDefinition.create(method, handler)
  }

  // ==================== Internal: Call Handlers ====================

  private fun unaryHandler(fullMethodName: String): ServerCallHandler<ByteArray, ByteArray> =
    ServerCalls.asyncUnaryCall { request, observer ->
      val metadata = MetadataCapturingInterceptor.currentMetadata()
      findAndProcessStub(fullMethodName, request, metadata).fold(
        ifEmpty = { observer.sendUnimplemented(fullMethodName) },
        ifSome = { (_, stub) -> stub.sendResponse(observer) }
      )
    }

  private fun serverStreamHandler(fullMethodName: String): ServerCallHandler<ByteArray, ByteArray> =
    ServerCalls.asyncServerStreamingCall { request, observer ->
      val metadata = MetadataCapturingInterceptor.currentMetadata()
      findAndProcessStub(fullMethodName, request, metadata).fold(
        ifEmpty = { observer.sendUnimplemented(fullMethodName) },
        ifSome = { (_, stub) -> stub.sendResponse(observer) }
      )
    }

  private fun clientStreamHandler(fullMethodName: String): ServerCallHandler<ByteArray, ByteArray> =
    ServerCalls.asyncClientStreamingCall { responseObserver ->
      val metadata = MetadataCapturingInterceptor.currentMetadata()
      CollectingStreamObserver(
        onComplete = { requests ->
          val requestBytes = requests.firstOrNull() ?: ByteArray(0)
          findAndProcessStub(fullMethodName, requestBytes, metadata).fold(
            ifEmpty = { responseObserver.sendUnimplemented(fullMethodName) },
            ifSome = { (_, stub) -> stub.sendResponse(responseObserver) }
          )
        },
        onStreamError = { responseObserver.onError(it) }
      )
    }

  private fun bidiStreamHandler(fullMethodName: String): ServerCallHandler<ByteArray, ByteArray> =
    ServerCalls.asyncBidiStreamingCall { responseObserver ->
      val metadata = MetadataCapturingInterceptor.currentMetadata()
      val requestChannel = Channel<ByteArray>(Channel.UNLIMITED)

      CoroutineScope(Dispatchers.IO).launch {
        stubs[fullMethodName]?.find { (_, stub) -> stub.metadataMatcher.matches(metadata) }?.let { (_, stub) ->
          when (stub) {
            is StubDefinition.BidiStream -> runCatching {
              stub.handler(requestChannel.consumeAsFlow()).collect { response ->
                responseObserver.onNext(response.toByteArray())
              }
              responseObserver.onCompleted()
            }.onFailure { e ->
              responseObserver.onError(Status.INTERNAL.withCause(e).asException())
            }

            is StubDefinition.Error -> responseObserver.onError(stub.toStatusException())

            else -> responseObserver.sendUnexpectedStubType("bidi stream")
          }
        } ?: responseObserver.sendUnimplemented(fullMethodName)
      }

      ChannelForwardingObserver(requestChannel, CoroutineScope(Dispatchers.IO))
    }

  // ==================== Extension Functions ====================

  private val StubDefinition.methodType: MethodDescriptor.MethodType
    get() = when (this) {
      is StubDefinition.Unary, is StubDefinition.Error -> MethodDescriptor.MethodType.UNARY
      is StubDefinition.ServerStream -> MethodDescriptor.MethodType.SERVER_STREAMING
      is StubDefinition.ClientStream -> MethodDescriptor.MethodType.CLIENT_STREAMING
      is StubDefinition.BidiStream -> MethodDescriptor.MethodType.BIDI_STREAMING
    }

  private fun RequestMatcher.matches(requestBytes: ByteArray): Boolean = when (this) {
    is RequestMatcher.Any -> true
    is RequestMatcher.ExactBytes -> requestBytes.contentEquals(bytes)
    is RequestMatcher.ExactMessage -> requestBytes.contentEquals(message.toByteArray())
    is RequestMatcher.Custom -> matcher(requestBytes)
  }

  private fun MetadataMatcher.matches(metadata: Metadata): Boolean = when (this) {
    is MetadataMatcher.Any -> {
      true
    }

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

    is MetadataMatcher.Custom -> {
      matcher(metadata)
    }

    is MetadataMatcher.All -> {
      matchers.all { it.matches(metadata) }
    }
  }

  private fun StubDefinition.sendResponse(observer: StreamObserver<ByteArray>) {
    when (this) {
      is StubDefinition.Unary -> observer.sendSingleAndComplete(response.toByteArray())
      is StubDefinition.ServerStream -> observer.sendAllAndComplete(responses.map { it.toByteArray() })
      is StubDefinition.ClientStream -> observer.sendSingleAndComplete(response.toByteArray())
      is StubDefinition.Error -> observer.onError(toStatusException())
      is StubDefinition.BidiStream -> observer.sendUnexpectedStubType("non-bidi call")
    }
  }

  private fun StubDefinition.Error.toStatusException(): StatusException =
    (message?.let { status.withDescription(it) } ?: status).asException()

  private fun StreamObserver<ByteArray>.sendSingleAndComplete(bytes: ByteArray) {
    onNext(bytes)
    onCompleted()
  }

  private fun StreamObserver<ByteArray>.sendAllAndComplete(bytesList: List<ByteArray>) {
    bytesList.forEach { onNext(it) }
    onCompleted()
  }

  private fun StreamObserver<*>.sendUnimplemented(fullMethodName: String) {
    onError(Status.UNIMPLEMENTED.withDescription("No matching stub for $fullMethodName").asException())
  }

  private fun StreamObserver<*>.sendUnexpectedStubType(context: String) {
    onError(Status.INTERNAL.withDescription("Unexpected stub type for $context").asException())
  }

  private fun String.toStubKey(): StubKey {
    val parts = split("/")
    require(parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
      "Invalid gRPC method name format: '$this'. Expected format: 'serviceName/methodName'"
    }
    return StubKey(parts[0], parts[1])
  }

  // ==================== Helper Classes ====================

  private class CollectingStreamObserver(
    private val onComplete: (List<ByteArray>) -> Unit,
    private val onStreamError: (Throwable) -> Unit
  ) : StreamObserver<ByteArray> {
    private val collected = mutableListOf<ByteArray>()

    override fun onNext(value: ByteArray) {
      collected.add(value)
    }

    override fun onError(t: Throwable) {
      onStreamError(t)
    }

    override fun onCompleted() {
      onComplete(collected)
    }
  }

  private class ChannelForwardingObserver(
    private val channel: Channel<ByteArray>,
    private val scope: CoroutineScope
  ) : StreamObserver<ByteArray> {
    override fun onNext(value: ByteArray) {
      // Use trySend for non-blocking operation, falling back to coroutine launch
      // if the channel buffer is full (which shouldn't happen with UNLIMITED capacity)
      val result = channel.trySend(value)
      if (result.isFailure && !result.isClosed) {
        scope.launch { channel.send(value) }
      }
    }

    override fun onError(t: Throwable) {
      channel.close(t)
    }

    override fun onCompleted() {
      channel.close()
    }
  }

  companion object {
    @GrpcMockDsl
    fun GrpcMockSystem.server(): Server = server
  }
}

/**
 * Interceptor that captures request metadata and makes it available via Context.
 */
private object MetadataCapturingInterceptor : ServerInterceptor {
  private val METADATA_KEY: Context.Key<Metadata> = Context.key("captured-metadata")

  fun currentMetadata(): Metadata = METADATA_KEY.get() ?: Metadata()

  override fun <ReqT, RespT> interceptCall(
    call: ServerCall<ReqT, RespT>,
    headers: Metadata,
    next: ServerCallHandler<ReqT, RespT>
  ): ServerCall.Listener<ReqT> {
    val context = Context.current().withValue(METADATA_KEY, headers)
    return Contexts.interceptCall(context, call, headers, next)
  }
}

private object ByteArrayMarshaller : MethodDescriptor.Marshaller<ByteArray> {
  override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)

  override fun parse(stream: InputStream): ByteArray = stream.readBytes()
}
